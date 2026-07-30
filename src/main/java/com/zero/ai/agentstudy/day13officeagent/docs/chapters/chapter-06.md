# 第 6 章 让大模型直接产出文档结构：officesummary 与结构化输出

## 6.1 为什么需要这一章

前五章我们打通了"一份 `DocumentIR` → Word/Excel/PPT/PDF 四种格式"的**渲染下半场**。但有一个根本问题一直悬而未决：**这份 `DocumentIR` 是从哪来的？**

在真实的 AI Office Agent 里，文档结构不该由程序员手写 `builder.heading(...).paragraph(...)` 拼出来——那样它就退化成一个模板引擎，跟"智能"毫无关系。真正的智能体应该能读一段原始销售数据、一份会议速记、一篇长报告，**自己想清楚该分几个章节、每章讲什么、哪些数字该做成要点**，然后产出结构化文档。

这就是本章要解决的**生成上半场**：用大模型的理解与组织能力，把"非结构化的原始信息"转化为"结构化的 `DocumentIR`"。技术抓手就是 Spring AI 2 的**结构化输出（Structured Output）**。

本章我们将实现 officesummary 模块，它是终极场景"根据昨天销售数据生成一份周报"的大脑。

## 6.2 结构化输出：从"解析文本"到"直接拿对象"

### 6.2.1 传统做法的痛

如果只用 `generateText`，你会拿到一段字符串。想把它变成结构化数据，就得手写解析：正则抠标题、按空行切段落、猜哪几行是要点……这套代码又长又脆，模型措辞稍变就崩。

### 6.2.2 Spring AI 的解法：`.entity(Class)`

Spring AI 2 的 `ChatClient` 提供了 `.call().entity(TargetType.class)`。它在背后做了三件事：

1. **反射目标类型，生成 JSON Schema**，并把 Schema 作为格式约束注入提示词；
2. 引导模型产出**符合 Schema 的 JSON**；
3. 用 Jackson 把 JSON **反序列化成目标对象**。

于是调用方一行代码就能拿到强类型对象：

```java
SummaryOutline outline = chatClient.prompt()
        .user(prompt)
        .call()
        .entity(SummaryOutline.class);
```

我们把这个能力沉淀在 [`ModelPort`](../../officecore/domain/port/ModelPort.java:38) 的 `generateStructured` 方法上，由 [`SpringAiModelAdapter`](../../officecore/adapter/SpringAiModelAdapter.java:83) 实现。领域层只面对 `ModelPort` 抽象，对 `ChatClient` 一无所知——依赖倒置一以贯之。

## 6.3 关键设计决策：不要让模型直接吐 DocumentIR

这是本章**最重要的一条工程经验**，很多人第一反应是"既然能结构化输出，那就让模型直接产出 `DocumentIR` 呗"。这条路走不通，原因有二：

### 6.3.1 DocumentIR 不适合当反序列化目标

回看 [`DocumentIR`](../../officecore/domain/ir/DocumentIR.java:23)：它是一个**私有构造器 + 无 setter + 内含 sealed `Block` 接口 + 多种内嵌 record + 防御性拷贝**的领域聚合根。

- 私有构造器 + 无 setter：Jackson 无从下手，无法反序列化;
- sealed `Block` 是多态接口：Jackson 不知道该把一个 JSON 节点反序列化成 `HeadingBlock` 还是 `TableBlock`，需要额外的类型标识配置，Schema 也会变得极其复杂;
- 即便强行打通，也会**为了迁就序列化而破坏领域模型的封装性与不变式**——本末倒置。

### 6.3.2 让模型同时管内容和结构，质量会下降

`DocumentIR` 有几十个字段（对齐、样式、colspan、缩进层级……）。让模型在填内容的同时正确填满这些结构字段，等于给它加了双重负担，幻觉与字段缺失的概率骤增。

### 6.3.3 正确姿势：LLM 产简单 DTO → 代码映射复杂聚合

我们的做法是引入一个**结构化输出专用 DTO** [`SummaryOutline`](../../officesummary/domain/SummaryOutline.java:26)：

```java
public record SummaryOutline(String title, String subtitle, List<Section> sections) {
    public record Section(String heading, List<String> paragraphs, List<String> bullets) { }
}
```

它是一个**扁平、纯 record、无多态、无私有构造器**的结构——Jackson 能轻松反序列化，Schema 也简单清晰，模型只需专注填文字。

拿到 `SummaryOutline` 后，交给确定性的领域服务 [`OutlineToIrMapper`](../../officesummary/domain/OutlineToIrMapper.java:28) 映射成完整的 `DocumentIR`：主标题→H1、副标题→段落、每章标题→H2、正文→段落、要点→无序列表，空字符串一律跳过。

> **一句话原则：让模型只做它擅长的事（填扁平字段），把结构组装交给确定性代码。** 这就是"确定性外壳 + 概率性内核"范式——用代码的确定性兜住模型的不确定性。

## 6.4 SpringAiModelAdapter：把 LLM 细节全部收进适配器

[`SpringAiModelAdapter`](../../officecore/adapter/SpringAiModelAdapter.java:29) 是 `ModelPort` 的出站适配器实现，是领域与 Spring AI 之间**唯一的翻译层**。

### 6.4.1 为什么注入 `ChatClient.Builder` 而非 `ChatClient`

```java
public SpringAiModelAdapter(ChatClient.Builder builder) {
    this.chatClient = builder.build();
}
```

`ChatClient.Builder` 是原型级 Bean，每个适配器可以按需定制自己的默认系统提示词、温度、超时等，互不干扰；若直接注入构建好的 `ChatClient`，则全应用共享一份配置，灵活性差。这是 Spring AI 官方推荐的注入方式。

### 6.4.2 系统提示词压制"多嘴"

结构化输出虽由框架注入 Schema，但模型仍常爱在 JSON 外补一段寒暄或解释，破坏解析。我们用一段系统提示词明确约束：

```java
private static final String DOCUMENT_SYSTEM_PROMPT = """
        你是一名专业的企业文档撰写助手……
        1. 只输出与文档结构对应的字段，不要输出任何寒暄、解释或额外说明；
        ……
        4. 忠实于给定数据，不要编造上下文中不存在的数字或事实。
        """;
```

"忠实于给定数据、不要编造"这一句尤其关键——它是压制**数据幻觉**的第一道防线，在生成销售周报这种对数字准确性零容忍的场景不可或缺。

### 6.4.3 三个方法各司其职

- `generateText`：纯文本，用于不需要结构的场景；
- `generateDocument`：面向"直接产出 DocumentIR"的语义方法（保留接口，运行期依赖 IR 可反序列化，生产中通常走 `generateStructured` + Mapper 这条更稳的路）；
- `generateStructured`：**本章主力**，泛型结构化输出，officesummary 全靠它产出 `SummaryOutline`。

## 6.5 DocumentSummaryService：三个场景一条链路

应用服务 [`DocumentSummaryService`](../../officesummary/application/DocumentSummaryService.java:26) 把"提示词 → 结构化输出 → 映射 IR"这条链路封装成三个业务方法：

| 方法 | 场景 | 输入 | 输出 |
| --- | --- | --- | --- |
| `summarize` | 通用长文总结 | 原始长文本 | 总结文档 IR |
| `generateWeeklySalesReport` | 销售周报 | 销售数据摘要 + 周期 | 周报 IR |
| `generateMeetingMinutes` | 会议纪要 | 会议速记 + 主题 | 纪要 IR |

三者共享同一条链路，差异只在**提示词模板**。把提示词工程集中在应用层，让领域层（`Outline` / `Mapper`）保持与 LLM 无关，是清晰的关注点分离。核心代码只有三行：

```java
SummaryOutline outline = modelPort.generateStructured(prompt, SummaryOutline.class);
return OutlineToIrMapper.map(outline, author, tenantId);
```

拿到 IR 之后，"渲染成什么格式、发给谁、存到哪"都与本服务无关——这正是第 3 章埋下的 IR 解耦在此刻兑现的红利。终极场景"根据昨天销售数据生成周报并做成 PPT 发给销售总监"的**第一棒**（数据→IR），就落在 `generateWeeklySalesReport` 上；后续的渲染、发送、归档将在第 7、8 章接力。

### 6.5.1 可测试性：依赖倒置的又一次兑现

`DocumentSummaryService` 只依赖 `ModelPort` 抽象。单元测试时注入一个返回固定 `SummaryOutline` 的假实现，就能在**完全脱离网络与真实模型**的情况下验证"映射逻辑、章节顺序、空值跳过"是否正确——这是把 LLM 依赖挡在端口之外带来的直接工程收益。

## 6.6 常见误区

1. **误区：让模型直接产出 `DocumentIR`。** → 私有构造器 + sealed 多态让 Jackson 无法反序列化，且会破坏领域封装。用扁平 DTO + Mapper。
2. **误区：结构化输出就不用写提示词了。** → Schema 只约束"形状"，不约束"质量"。仍需系统提示词压制寒暄、约束忠实于数据。
3. **误区：把提示词硬编码进领域层。** → 提示词是易变的应用策略，应集中在应用服务，领域层保持与 LLM 无关。
4. **误区：不给结构化输出兜底。** → 模型可能返回空或字段缺失。`SummaryOutline` 用紧凑构造器做非空归一，`Mapper` 对空大纲返回"（无内容）"占位文档，绝不返回 `null`。
5. **误区：把 `ChatClient` 当单例到处共享。** → 注入 `ChatClient.Builder`，每个适配器构建自己的实例，便于差异化配置。
6. **误区：信任模型填的数字。** → 生成类场景务必在提示词中强约束"只用给定数据"，并在关键场景对数字做二次校验（可放在 Pipeline 校验阶段）。

## 6.7 小结与思考题

本章我们打通了 Office Agent 的**生成上半场**：

- 用 Spring AI 2 的 `.entity(Class)` 把"解析文本"升级为"直接拿强类型对象"；
- 确立了核心工程范式：**LLM 产扁平 DTO（`SummaryOutline`）→ 确定性代码映射复杂聚合（`DocumentIR`）**；
- `SpringAiModelAdapter` 把所有 LLM 细节收进适配器，领域只认 `ModelPort`；
- `DocumentSummaryService` 用一条链路支撑总结/周报/纪要三大场景，并天然可测试。

至此，"原始数据 → DocumentIR → 四格式渲染"的主干已全线贯通。

**思考题：**

1. 如果一份报告需要在文中插入一张由 `ChartBlock` 表达的柱状图，你会如何扩展 `SummaryOutline`？是让模型产出图表数据，还是让代码根据原始数据计算后再插入？各有什么取舍？
2. `OutlineToIrMapper` 目前把要点固定映射为无序列表。如果希望模型自己决定用有序还是无序，应该在 DTO 里加什么字段？会不会又落回"让模型管结构"的陷阱？
3. 结构化输出偶尔会因模型返回非法 JSON 而反序列化失败。你会在哪一层加重试？重试时是否应该调高温度或换更强的模型？
4. 如何为 `generateWeeklySalesReport` 编写一个不依赖真实大模型的单元测试？请描述你会如何设计 `ModelPort` 的假实现与断言点。

下一章，我们将实现 officemail / officecalendar / officetask / officetemplate 四个模块，让 Agent 不仅能"生成文档"，还能"把文档发出去、把事情安排下去"——真正打通办公自动化的最后一公里。