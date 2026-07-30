# 第三章 officecore 领域核心：把「文档」建模成一棵可被 AI 生成、可被多格式渲染的语义树

> 本章是整个 Day13 的地基。读完你将拥有一套**与格式彻底解耦、可被大模型结构化输出、可被并行渲染、可断点续跑、可多租户隔离**的领域内核。后续所有章节（Word/Excel/PPT/PDF 渲染、总结、邮件、日历、Workflow 编排）都建立在本章的类型之上。

---

## 3.1 为什么要先写 officecore：领域内核决定系统的天花板

在动手写"用 POI 生成一个 Word"之前，我们必须先回答一个更根本的问题：

> **一份文档，在系统内部到底应该长什么样？**

绝大多数"AI 生成 Office"的项目死在了这个问题上。它们跳过了领域建模，直接让大模型吐出 POI 调用代码，或者直接生成二制。结果就是：

- 改一次排版要重写生成逻辑（内容与格式强耦合）；
- 要同时出 Word/PDF/PPT，得把生成逻辑复制三份；
- 大模型幻觉直接写进最终文件，渲染前无法校验；
- 无法做审批（因为没有一个可读、可改的中间态）；
- 无法断点续跑（因为没有一个可持久化的状态载体）。

`officecore` 模块存在的意义，就是用一套**领域模型**把这些问题一次性解决。它不依赖 POI、不依赖 Spring AI、不依赖任何具体框架——它是纯粹的业务语义。这正是六边形架构（Hexagonal Architecture）的"内核"：**高层策略不依赖低层细节，两者都依赖抽象**。

`officecore` 的目录结构如下（本章创建的全部文件）：

```
officecore/
└── domain/
    ├── ir/                         # 文档中间表示（Document IR）
    │   ├── DocumentFormat.java     # 目标格式枚举
    │   ├── TextStyle.java          # 文本样式值对象
    │   ├── Run.java                # 富文本片段
    │   ├── Block.java              # 块密封接口（sealed）
    │   ├── HeadingBlock.java       # 标题块
    │   ├── ParagraphBlock.java     # 段落块
    │   ├── TableBlock.java         # 表格块
    │   ├── ListBlock.java         # 列表块
    │   ├── ChartBlock.java         # 图表块
    │   ├── ImageBlock.java         # 图片块
    │   ├── PageBreakBlock.java     # 分页符块
    │   ├── DocumentMetadata.java   # 文档元数据
    │   └── DocumentIR.java         # 聚合根：元数据 + 块列表
    ├── context/                    # 工作流上下文
    │   ├── TenantContext.java      # 多租户上下文
    │   ├── PipelineStage.java      # 七阶段枚举
    │   └── OfficeContext.java      # 贯穿 Pipeline 的共享工作台
    ├── task/                       # 任务聚合
    │   ├── TaskStatus.java         # 任务状态机
    │   └── OfficeTask.java         # 任务聚合根
    └── port/                       # 六边形出站端口
        ├── DocumentRenderer.java   # 渲染端口
        ├── MailSender.java         # 邮件端口
        ├── FileStorage.java        # 存储端口
        ├── KnowledgeStore.java     # 知识库端口
        ├── OcrEngine.java          # OCR 端口
        └── ModelPort.java          # 大模型端口
```

三个子包对应三种领域概念：**IR（文档长什么样）**、**context/task（任务如何流转）**、**port（领域需要外界提供什么能力）**。

---

## 3.2 Document IR：文档的「与格式无关」中间表示

### 3.2.1 核心思想：块（Block）+ 运行（Run）的双层富文本模型

我们把"一份文档"建模成一棵极简的树：

```
DocumentIR
├── DocumentMetadata（标题/作者/租户/创建时间/自定义属性）
└── List<Block>（有序块列表）
    ├── HeadingBlock   → List<Run>
    ├── ParagraphBlock → List<Run> + Alignment
    ├── TableBlock     → header/rows（单元格内是 List<Run>）
    ├── ListBlock      → List<ListItem>（项内是 List<Run>）
    ├── ChartBlock     → 图表类型 + 类目 + 数据系列
    ├── ImageBlock     → 图片字节 + 尺寸
    └── PageBreakBlock  → 分页标记
```

-**Block（块）** 是"一个独立的语义单元"：一个标题、一个段落、一张表、一个列表、一张图表、一张图片、一个分页符。
- **Run（运行片段）** 是"一段样式一致的文字"，是富文本的最小单元。一个段落由多个 Run 组成，从而支持"段落内局部加粗、局部标红"。

为什么要拆成 Block + Run 两层？因为真实办公文档的诉求是：**结构层面按块组织，样式层面按片段着色**。例如一句"本季度增长 **32%**，超额完成目标"，在 IR 里就是一个 `ParagraphBlock`，内部有三个 `Run`：普通样式的"本季度增长 "、加粗样式的"32%"、普通样式的"，超额完成目标"。

### 3.2.2 Run 与 TextStyle：富文本的最小单元

先看最基础的两个值对象。[`TextStyle`](../../officecore/domain/ir/TextStyle.java:1) 描述"这段文字长什么样"——是否加粗/斜体/下划线、字号、颜色，并用**不可变 + 链式派生**的方式提供样式：

```java
TextStyle style = TextStyle.normal().bold().size(14).color("#C00000");
```

[`Run`](../../officecore/domain/ir/Run.java:14) 则把"文字 + 样式"绑在一起，并做了防御式空值处理（text 为 null 归一为空串、style 为 null 归一为 normal），保证下游渲染器永远拿不到 null：

```java
public record Run(String text, TextStyle style) {
    public Run {
        if (text == null) text = "";
        if (style == null) style = TextStyle.normal();
    }
    public static Run of(String text) { return new Run(text, TextStyle.normal()); }
    public static Run strong(String text) { return new Run(text, TextStyle.strong()); }
}
```

> **设计要点**：所有 IR 类型都是 Java 21 `record`（值对象），天然不可变。不可变性是后面"多格式并行渲染时安全共享同一棵 IR 树"的前提——没有可变状态，就没有并发问题。

### 3.2.3 Block 密封接口：用编译期约束换渲染期安全

[`Block`](../../officecore/domain/ir/Block.java:17) 是所有块的共同抽象。我们用 Java 21 的**密封接口（sealed interface）**把"合法的块类型"锁死在编译期：

```java
public sealed interface Block
        permits HeadingBlock, ParagraphBlock, TableBlock, ListBlock,
                ChartBlock, ImageBlock, PageBreakBlock {
    BlockType type();
    enum BlockType { HEADING, PARAGRAPH, TABLE, LIST, CHART, IMAGE, PAGE_BREAK }
}
```

为什么用 `sealed` 而不是普通 `interface`？因为渲染器（第四章起）要对每种块做不同处理，最自然的写法是 `switch` 模式匹配：

```java
String out = switch (block) {
    case HeadingBlock h   -> renderHeading(h);
    case ParagraphBlock p -> renderParagraph(p);
    case TableBlock t     -> renderTable(t);
    case ListBlock l      -> renderList(l);
    case ChartBlock c     -> renderChart(c);
    case ImageBlock i     -> renderImage(i);
    case PageBreakBlock pb -> renderPageBreak(pb);
    // 无需 default —— 编译器已知所有子类型，穷尽检查
};
```

密封接口让编译器知道"块只有这七种"，于是 `switch` 可以做**穷尽检查（exhaustiveness）**：将来如果新增一种块（比如 `FootnoteBlock`），所有 `switch` 会立即编译报错，强制每个渲染器补齐分支。**这就是用编译期约束换渲染期安全**——把"漏处理一种块"从线上事故提前到编译失败。

### 3.2.4 七种块：从标题到图表

每种块都是一个实现 `Block` 的 record，只描述"内容与结构"，不碰任何格式：

| 块类型 | 承载什么 | 关键字段 | 典型来源 |
|---|---|---|---|
| [`HeadingBlock`](../../officecore/domain/ir/HeadingBlock.java:17) | 多级标题 H1~H6 | `level` + `List<Run>` | 目录/大纲的基础 |
| [`ParagraphBlock`](../../officecore/domain/ir/ParagraphBlock.java:16) | 正文段落 | `List<Run>` + `Alignment` | 结论、描述 |
| [`TableBlock`](../../officecore/domain/ir/TableBlock.java:22) | 二维结构化数据 | `header` + `rows`（单元格富文本） | Excel 读来的数据 |
| [`ListBlock`](../../officecore/domain/ir/ListBlock.java:16) | 有序/无序列表 | `ordered` + `List<ListItem>` | 待办、完成项 |
| [`ChartBlock`](../../officecore/domain/ir/ChartBlock.java:20) | 数据可视化 | `chartType` + `categories` + `series` | 销售趋势图 |
| [`ImageBlock`](../../officecore/domain/ir/ImageBlock.java:19) | 内联图片 | `data`(字节) + 尺寸 | Logo、图表图片、扫描件 |
| [`PageBreakBlock`](../../officecore/domain/ir/PageBreakBlock.java:13) | 分页/切页 | 无状态标记 | PPT 每页、PDF 分页 |

几个值得注意的**领域约束**（都写在紧凑构造器里，构造即校验）：

1. **`HeadingBlock` 校验 `level` 必须在 1~6**，非法级别直接抛异常，绝不让脏数据流到渲染阶段才在 POI 层报一个莫名其妙的错。
2. **`TableBlock` 校验每行列数与表头一致**——残缺表格是渲染引擎最常见的崩溃源，这里提前拦截：

```java
int columnCount = header.size();
for (int i = 0; i < rows.size(); i++) {
    if (rows.get(i).size() != columnCount) {
        throw new IllegalArgumentException(
            "第 " + (i + 1) + " 行列数(" + rows.get(i).size()
                + ")与表头列数(" + columnCount + ")不一致");
    }
}
```

3. **`ImageBlock` 用内联字节而非文件路径**承载图片，并在构造器与访问器双向做防御性拷贝（`data.clone()`），保证 IR 可跨进程序列化传递、且不可变性不被外部字节数组篡改破坏。
4. **`PageBreakBlock` 是无状态单例**（`PageBreakBlock.instance()`），避免为纯标记重复创建对象。

### 3.2.5 DocumentIR：聚合根与 Builder

[`DocumentIR`](../../officecore/domain/ir/DocumentIR.java:23) 是把元数据和块列表组合起来的聚合根，也是**大模型结构化输出的目标类型**和**渲染器的输入类型**。它提供三种构建方式：

```java
// 方式一：Builder 流式构建（人写代码时最顺手）
DocumentIR doc = DocumentIR.builder()
    .metadata(DocumentMetadata.of("Q3 销售周报", "AI Office Agent"))
    .heading(1, "销售周报")
    .paragraph("本周整体表现超出预期。")
    .block(TableBlock.of(...))
    .pageBreak()
    .build();

// 方式二：直接 of（反序列化/结构化输出时）
DocumentIR doc = DocumentIR.of(metadata, blocks);

// 方式三：写时复制派生（Pipeline 阶段间增量修改）
DocumentIR enriched = doc.append(List.of(chartBlock));
```

`append` 与 `withMetadata` 都返回**新实例**而非修改自身，延续了整棵树的不可变性——这让 IR 可以在多个渲染线程之间无锁共享。

---

## 3.3 context / task：任务如何流转

IR 回答了"文档长什么样"，接下来要回答"一次生成任务如何被驱动、被隔离、被恢复"。

### 3.3.1 TenantContext：多租户隔离凭证

[`TenantContext`](../../officecore/domain/context/TenantContext.java:19) 是贯穿全链路的"隔离凭证"。企业级 Office Agent 通常是多租户 SaaS——不同企业客户共用一套服务，但**数据、资源、计量、配置必须严格隔离**。它携带 `tenantId / userId / plan`，让每个适配器都能据此做正确决策：文件存储分桶、知识库分库、模型调用分租户计量与限额。

关键设计：**把租户信息显式地随上下文传递，而不是藏在 ThreadLocal 里**。因为我们后面要用 Virtual Threads + 结构化并发做并行渲染，ThreadLocal 会在跨线程时丢失，而显式传参永远可靠。

### 3.3.2 PipelineStage：七阶段处理模型

[`PipelineStage`](../../officecore/domain/context/PipelineStage.java:22) 把一次复杂任务拆成七个职责单一的阶段：

```
PERCEIVE(感知) → PLAN(规划) → GENERATE(生成) → RENDER(渲染)
    → REVIEW(审批) → DELIVER(交付) → OBSERVE(观测)
```

每个阶段职责单一、可独立测试、可断点续跑。这与第二章讲的责任链 + 状态机模型一一对应：责任链按顺序驱动阶段，状态机守护合法流转。

### 3.3.3 OfficeContext：贯穿 Pipeline 的共享工作台

[`OfficeContext`](../../officecore/domain/context/OfficeContext.java:26) 是七阶段之间传递的"工作台"。每个阶段处理器读写同一个上下文：

- 感知阶段 → `put("salesData", ...)` 写入原始输入；
- 生成阶段 → `setDocumentIR(ir)` 写入文档 IR；
- 渲染阶段 → `addArtifact(PDF, bytes)` 写入各格式产物；
- 交付阶段 → `artifact(PDF)` 取出产物去分发。

它用 `ConcurrentHashMap` 保证多线程写入安全，并用 `advanceTo(stage)` 记录阶段完成时间戳。最关键的价值是**断点续跑**：因为上下文完整记录了"任务进行到哪一步、已经产出了什么"，只要能把它序列化持久化，任务中断后即可从 `currentStage` 恢复，而不必从头重跑一遍昂贵的大模型调用。

### 3.3.4 OfficeTask + TaskStatus：聚合根与状态机

[`OfficeTask`](../../officecore/domain/task/OfficeTask.java:23) 是 DDD 聚合根，是"一次作业"的唯一事实来源，封装了指令、目标格式、租户归属和状态。所有状态变更都必须经过聚合根方法（`start / awaitApproval / resume / complete / fail / cancel`），从而把业务规则收拢进领域模型。

状态流转由 [`TaskStatus`](../../officecore/domain/task/TaskStatus.java:14) 状态机守护：

```
CREATED ──start──▶ RUNNING ──complete──▶ COMPLETED(终态)
             │        │
             │        ├──awaitApproval──▶ WAITING_APPROVAL ──resume──▶ RUNNING
             │        ├──fail──▶ FAILED(终态)
             └────────┴──cancel──▶ CANCELLED(终态)
```

`canTransitionTo` 用 `switch` 表达式把合法迁移集合写死，任何非法跃迁（比如"已完成的任务又被改回运行中"）都会抛 `IllegalStateException`：

```java
private void transition(TaskStatus target) {
    if (!status.canTransitionTo(target)) {
        throw new IllegalStateException("非法状态迁移：" + status + " → " + target);
    }
    this.status = target;
    this.updatedAt = Instant.now();
}
```

> **DDD 要点**：状态机逻辑放在**枚举**里而非 Service 里，是"把业务规则收拢进领域对象"的典型手法。规则集中一处，任何调用方都无法绕过。

---

## 3.4 port：六边形的出站端口——依赖倒置的落地

领域内核要完成任务，需要外界提供能力：渲染文档、发邮件、存文件、写知识库、OCR、调大模型。但领域**绝不能直接依赖** POI、Jakarta Mail、S3 SDK、Spring AI——那会让框架细节污染内核。解法是**定义端口（接口），让适配器去实现**。

本模块定义了六个出站端口，全部放在 `domain/port/`：

| 端口 | 领域诉求 | 未来的适配器实现 |
|---|---|---|
| [`DocumentRenderer`](../../officecore/domain/port/DocumentRenderer.java:18) | 把 IR 渲染成某格式二进制 | POI / docx4j / PDFBox |
| [`MailSender`](../../officecore/domain/port/MailSender.java:13) | 发送带附件的邮件 | Jakarta Mail SMTP |
| [`FileStorage`](../../officecore/domain/port/FileStorage.java:16) | 保存/读取/删除文件 | 本地 FS / MinIO / S3 |
| [`KnowledgeStore`](../../officecore/domain/port/KnowledgeStore.java:17) | 文档入库与语义检索 | pgvector（复用 Day1~12 RAG） |
| [`OcrEngine`](../../officecore/domain/port/OcrEngine.java:14) | 图片文字识别 | Tesseract / 云 OCR / 多模态 |
| [`ModelPort`](../../officecore/domain/port/ModelPort.java:19) | 文本 + 结构化输出 | Spring AI ChatClient |

### 3.4.1 DocumentRenderer：一格式一实现，支撑并行渲染

```java
public interface DocumentRenderer {
    DocumentFormat format();          // 我负责哪种格式
    byte[] render(DocumentIR ir);     // 把 IR 变成字节
    default boolean supports(DocumentIR ir) { return ir != null; }
}
```

每种格式一个实现（`WordRenderer` / `PdfRenderer` / …），Pipeline 渲染阶段按 `format()` 选择渲染器，并用 Virtual Threads 并行渲染多格式，彼此隔离。这是"一份 IR，多格式输出"能力的接口基础。

### 3.4.2 ModelPort：把「结构化输出」抽象成领域契约

`ModelPort` 是最能体现依赖倒置价值的端口。它把领域真正需要的能力抽象出来，**尤其是结构化输出**——直接把模型响应解析成 `DocumentIR`：

```java
public interface ModelPort {
    String generateText(String prompt);
    DocumentIR generateDocument(String instruction, String context);   // 结构化输出为 IR
    <T> T generateStructured(String prompt, Class<T> targetType);       // 通用结构化
}
```

将来适配器用 Spring AI 的 `ChatClient.prompt().user(...).call().entity(DocumentIR.class)` 实现它。而领域与 Pipeline 只依赖这个接口——**把底层模型从 OpenAI 换成本地部署，或在单测里注入一个确定性假实现，领域代码一行不改**。这就是依赖倒置的红利。

---

## 3.5 常见误区

1. **误区：让大模型直接生成 .docx/POI 代码，跳过 IR。** → 后果是内容格式强耦合、无法多格式、无法校验、无法审批。IR 是这一切的前提，不能省。
2. **误区：Block 用普通 interface 而非 sealed。** → 失去穷尽检查，新增块类型时渲染器悄悄漏处理，变成线上事故。
3. **误区：把租户信息放 ThreadLocal。** → 一旦用虚拟线程/结构化并发就会丢失。要显式随 `TenantContext` 传递。
4. **误区：状态流转逻辑写在 Service 里。** → 规则散落、容易被绕过。要收拢进 `TaskStatus` 枚举 + 聚合根方法。
5. **误区：领域直接 import POI / Spring AI。** → 框架细节污染内核，无法替换、无法单测。要通过端口隔离。
6. **误区：IR 用可变对象。** → 并行渲染共享时出现并发问题。要用 record + 写时复制保持不可变。

---

## 3.6 小结与思考题

本章我们完成了 `officecore` 领域内核：

- **IR 层**用"块 + Run"双层富文本模型，把文档建模成与格式无关的语义树；用密封接口换来渲染期的穷尽安全；用不可变 record 换来并行渲染的无锁共享。
- **context/task 层**用 `OfficeContext` 承载可断点续跑的工作流状态，用 `OfficeTask` + `TaskStatus` 状态机把业务规则收拢进领域。
- **port 层**用六个出站端口实现依赖倒置，让领域不依赖任何具体框架。

**思考题：**

1. 为什么 `TableBlock` 要在构造器里校验行列一致，而不是等渲染时再报错？这体现了什么设计原则？
2. `Block` 用 `sealed` 后，新增一种块类型会发生什么？相比普通 interface 有什么工程价值？
3. `OfficeContext` 如果要支持断点续跑，需要额外解决哪些序列化问题（提示：`DocumentIR` 里的图片字节、`ModelPort` 的中间态）？
4. 为什么 `ModelPort` 要单独暴露一个 `generateDocument` 返回 `DocumentIR`，而不是让上层自己调 `generateStructured`？

下一章，我们将新增 Apache POI 依赖，实现第一批渲染适配器：`WordRenderer` 与 `ExcelRenderer`，把本章的 IR 树真正变成可打开的 .docx 和 .xlsx 文件。