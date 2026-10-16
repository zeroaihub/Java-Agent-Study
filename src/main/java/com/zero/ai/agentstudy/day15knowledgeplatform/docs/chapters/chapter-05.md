# 第 5 章 · knowledge-chunk：三种切分策略与 Chunk 装配

> 「Chunk 是检索的最小单元，切得好不好，直接决定召回率的上限。」
>
> 解析拿到了纯文本，但它可能是一篇 50 页的制度、一份 3000 行的表格。
> 向量模型吃不下整篇，也无法精确定位到段落——必须切。

---

## 一、学习目标

学完本章，你将能够：

1. 说清楚 **Chunk 太大 / 太小** 分别会造成什么问题，以及为什么 **overlap 不可省**。
2. 掌握 **定长 / 语义 / 结构化** 三种切分策略的实现、适用场景与代价。
3. 理解为什么 **结构化切分召回质量最高**，以及它需要上游解析器配合什么。
4. 理解 **Chunk 必须自带租户与权限维度**（否则检索时无法下推过滤）。
5. 会用 **Token 估算** 控制 Prompt 预算，而不必在每个阶段加载真实 tokenizer。

---

## 二、为什么切分是 RAG 里最被低估的一环

大多数人把精力花在「换更好的 Embedding 模型」上，但实测中**切分策略带来的召回率提升，往往大于换模型**。

原因很直接：向量是把一整段文本压缩成一个点。这段文本里**混杂的主题越多，这个点的语义就越模糊**。

| 问题 | 表现 |
| --- | --- |
| **Chunk 太大** | 一片塞进整页，向量被稀释，问「住宿标准」和问「审批流程」都命中同一片，答案抓不住重点 |
| **Chunk 太小** | 切得太碎，语义被割裂，检索到的片段缺乏上下文，答案支离破碎 |
| **无 overlap** | 关键数字被切缝切断，两片都失去语义 |
| **按字符硬切** | 句子被拦腰斩断，送给 LLM 的上下文不连贯 |

---

## 三、代码逐行精讲

本章新增代码位于 `day15knowledgeplatform/chunk/`：

```
chunk/
├── ChunkingService.java       应用服务 + 策略自动选择
├── TokenEstimator.java        Token 估算
├── model/Chunk.java           切片（不可变）
├── port/ChunkStrategy.java    策略端口
└── strategy/
    ├── FixedSizeChunkStrategy.java     定长 + 重叠
    ├── SemanticChunkStrategy.java      按句边界
    └── StructuredChunkStrategy.java    按标题层级
```

### 3.1 策略端口：只切文本，不造 Chunk

```java
public interface ChunkStrategy {
    String name();
    List<String> split(String text);
}
```

刻意设计成 `String -> List<String>`：**策略只负责「切成几段」，装配 Chunk（ID、序号、元数据、租户注入）由 `ChunkingService` 统一做**。

这样策略就是纯函数：无状态、易测试、可随意替换。

### 3.2 定长切分：必须有 overlap

```java
int step = chunkSize - overlap;
while (start < length) {
    int end = Math.min(start + chunkSize, length);
    chunks.add(text.substring(start, end).trim());
    if (end == length) break;
    start += step;
}
```

**为什么必须有 overlap？** 因为语义会被切缝切断。Demo 里的真实例子：

```
原文: 报销标准：总监级别每晚上限 800 元，经理级别 500 元。

无重叠: [报销标准：总监级别每晚上限 800 元，] [经理级别 500 元。]
有重叠: [报销标准：总监级别每晚上限 800 元，] [限 800 元，经理级别 500 元。]
```

无重叠时，「800」和「元」分家，两片单独看都失去了关键语义，向量匹配都不准。**重叠一段字符能显著提高边界信息被完整保留的概率。**

代价：overlap 越大冗余越多，向量库体积与 Embedding 成本上升。经验值取 chunkSize 的 **10%~20%**。

### 3.3 语义切分：尊重语言边界

```java
private static final Pattern SENTENCE_BOUNDARY =
        Pattern.compile("(?<=[。！？；!?;])\\s*|\\n{2,}");
```

反过来做：**先按句拆分，再贪心打包到接近上限**。保证每个切片边界都落在句末。

单句超过上限时只能单独成片（否则永远合不进去）；最后一片过短则并入前一片，避免产生「碎片 Chunk」。

**代价**：切片大小不均匀；对无标点文本（日志、代码）效果退化——此时应改用定长。

### 3.4 结构化切分：召回质量最高

```java
private static final Pattern HEADING = Pattern.compile("(?m)^\\s{0,3}(#{1,6})\\s+(.*)$");
```

按标题层级切，两个理由：

1. **语义完整**：一个二级标题下讲的是同一件事，不会像定长切分那样把两个主题混在一片。
2. **可携带章节路径**：每片带上「三、审批流程」这样的路径，用户一眼知道答案出自哪一节——**可溯源的基础**。

```java
private static String buildPath(List<String> titles, List<Integer> levels, int index) {
    // 向上找所有层级更浅的祖先标题，拼成 A > B > C
}
```

**它与第 4 章的关键衔接**：Word 解析器把标题样式转成了 `#` 标记、Markdown 天然带 `#`，两条链路在本策略汇合，让 Word / Markdown / HTML 都能享受结构化切分。

**兜底**：某节过长时内部回退定长切分，但每片都带上 `[章节: xxx]` 前缀，保证溯源不断。

**无标题时**：退化成语义切分，至少不会切出垃圾。

### 3.5 Chunk：为什么用不可变 record

第 1 章说过 Chunk 是实体，这里却用 `record`——**这是刻意的**。

Chunk 是一类特殊实体：**创建后即定型**。文本变了？那不是「修改 Chunk」，而是一次重新切分——因为向量必须重算、序号和字符数也全变了。允许原地修改只会制造「文本已改、向量未更新」的不一致状态，**这正是向量漂移的温床**。

```java
public record Chunk(ChunkId id, TenantId tenantId, WorkspaceId workspaceId,
                    DocumentId documentId, int sequence, String text,
                    int charCount, int tokenEstimate, Metadata metadata)
```

**注意 `tenantId` 与 `workspaceId` 是 Chunk 的字段**，不是运行时才去关联查询。

> **这是本章最重要的设计点**：很多人切完只存 `{text, embedding}`，等检索时才发现无法按租户/部门过滤，
> 只能全库扫描后再在应用层丢弃——**既慢又危险**（这正是 Confluence AI 强调「权限必须前置到检索层」的原因）。
> 我们在切片生成时就把这些维度焊死在 Chunk 上，检索时可直接下推为过滤条件。

### 3.6 TokenEstimator：够用就好的近似值

```java
// 中日韩字符 ≈ 1 token/字；其余 ≈ 4 字符/token
double tokens = cjk + other / 4.0;
```

精确计算要加载对应模型的 tokenizer，在切分阶段为每段都跑一遍太重。这里只需要回答两个问题：**会不会超长**、**Prompt 预算够不够**。估算刻意**偏大**——宁可多估触发切分，也不要低估把超长片段塞进 Prompt。

### 3.7 策略自动选择

```java
public static ChunkStrategy selectStrategy(DocumentFormat format) {
    return switch (format) {
        case MD, HTML, DOCX, PPTX -> new StructuredChunkStrategy();
        case TXT -> new SemanticChunkStrategy();
        case PDF, XLSX, IMAGE, UNKNOWN -> new FixedSizeChunkStrategy();
    };
}
```

把「哪种文档该用哪种切法」固化下来。有标题结构的走结构化；纯文本走语义；Excel 已被行序列化、PDF 通常无结构，走定长。

---

## 四、架构设计思想

1. **纯函数策略 + 统一装配**：策略无状态易测试，装配逻辑集中在一处，元数据不会漏填。
2. **不可变 Chunk 从源头堵住向量漂移**：想改就重新切，不存在「改了一半」的中间态。
3. **维度前置**：租户/空间/文档/序号/章节路径全部在切片生成时写入，为后续权限下推与溯源提供数据基础。
4. **每个策略都有退路**：无标题 → 语义；章节过长 → 定长+路径前缀。任何输入都不会产出垃圾。

---

## 五、企业实战案例

### 案例：某律所合同检索的「切分改造」

某律所把 8000 份合同模板接入平台，律师反馈「搜到的段落总是答非所问」。

**原方案**：定长 1000 字切分，无重叠。

**问题**：合同是典型的强结构文档（第一条/第二条/甲方/乙方/违约责任）。1000 字的窗口经常**横跨两个条款**，于是「违约金比例」和「保密期限」被压进同一个向量，两个问题的检索结果完全一致。

**改造**：改用结构化切分，按条款标题切，每片带上路径（如 `三、违约责任 > 3.2 逾期付款`）。

**效果**：

- 检索准确率（人工抽检 100 条）从 52% → 91%；
- 律师能直接看到答案出自哪一条哪一款，**愿意信任并采用**（此前因为无法溯源，律师一律回到人工翻原文）。

**启示**：结构化切分的收益不只是召回率，更是**可溯源带来的信任**。没有溯源，再准的答案用户也不敢用。

---

## 六、面试高频题

1. **Chunk 大小怎么定？有万能值吗？**
   没有。取决于文档类型与 Embedding 模型的有效上下文。经验起点：中文 300~500 字（约 200~400 token），英文 500~800 token。**必须用真实问题集做召回率评测来调**，不能拍脑袋。

2. **overlap 设多少？太大会怎样？**
   通常取 chunkSize 的 10%~20%。太大则冗余剧增：向量库体积、Embedding 调用成本、检索时的重复结果都会上升，且可能让多个 Chunk 高度相似，挤占 Top-K 名额。

3. **为什么结构化切分通常优于定长？**
   因为它切在语义边界上（主题切换处），使得单个向量的语义更纯净；同时天然携带章节路径，支持溯源。前提是文档有结构且解析阶段保留了结构标记。

4. **Chunk 为什么要把 tenantId 冗余存进来？**
   为了把权限过滤下推到检索阶段。若 Chunk 不带租户维度，检索只能全库召回再过滤，既浪费算力，又存在「先取到无权数据」的越权风险。Confluence AI 的权限继承正是这个思路。

5. **换 Embedding 模型后需要重新切分吗？**
   切分本身不必重做，但**必须全量重算向量**。新旧模型产出的向量不在同一空间，混在一起检索必然崩坏（向量漂移）。这也是第 3 章把「版本 + 重跑流水线」建模为一等公民的原因。

---

## 七、扩展阅读

- LlamaIndex `NodeParser` / `SentenceSplitter` —— 对比开源实现的切分抽象。
- Haystack `DocumentSplitter` —— 按词/句/段落的多档切分。
- Pinecone / Weaviate 官方博客 Chunking Strategies —— 不同场景的实测对比。
- 本项目 Day05 `TextSplitter` —— 对比阅读：教学版最简切分实现，与本章三种策略的差距。

---

## 八、本章小结

- **Chunk 太大稀释语义、太小割裂语义**，overlap 是必需品而非常规优化项。
- **定长**：通用、均匀，但会切断句子；**语义**：边界落在句末，大小不均；**结构化**：按标题切，召回质量最高且可溯源。
- **结构化切分依赖第 4 章保留 `#` 标记**——这是解析与切分两章的隐藏契约。
- **Chunk 不可变**，想改就重新切，从源头堵住向量漂移。
- **租户/空间/文档/序号/路径必须焊死在 Chunk 上**，为权限下推与溯源打基础。
- **策略自动选择**把「哪种文档该怎么切」固化下来。

### 运行验证

```bash
java -cp target/classes:<poi/pdfbox 依赖> \
  com.zero.ai.agentstudy.day15knowledgeplatform.demo.Chapter05Demo
```

真实输出节选（同一份 293 字制度文档，三种策略对比）：

```
--- 定长切分（120 字 / 重叠 20 字） ---
  切片数=3，平均长度=110
  [1] (116字) 0 元。一线城市上浮 20%。 ## 二、交通标准 高铁二等座，飞机经济舱。...
      ↑ 注意开头是「0 元。」——「800」被切缝切断了

--- 语义切分（按句边界，最长 120） ---
  切片数=3，平均长度=94
  [1] (118字) ## 二、交通标准 高铁二等座，飞机经济舱。 总监级别可乘坐高铁一等座。...
      ↑ 边界落在句末，读起来是完整的几句话

--- 结构化切分（按 # 标题，最长 800） ---
  切片数=5，平均长度=56
  [1] (67字) ## 一、住宿标准 总监级别每晚上限 800 元。经理级别每晚上限 500 元。...
      ↑ 一片一个主题，且自带章节标题
```

再看 Chunk 装配结果——租户、序号、Token 估算、部门元数据全部就位：

```
ck-8cd53877... seq=0 chars=45 tokens≈33 | # 员工报销管理制度 本制度自 2026 年 1 月 1 日起执行...
ck-515a6ded... seq=1 chars=68 tokens≈50 | ## 一、住宿标准 总监级别每晚上限 800 元。...
租户=acme，部门=finance
```

---

## 附：Day15 前三章的端到端串联

```bash
java -cp target/classes:<poi/pdfbox 依赖> \
  com.zero.ai.agentstudy.day15knowledgeplatform.demo.IngestionPipelineDemo
```

```
[1] 上传: doc-203d0347... 状态=UPLOADED
[2] 解析: 状态=PARSED 字符数=138
[3] 切分: 状态=CHUNKED 切片数=3 总Token≈96
[4] 入库: 状态=INDEXED

切片明细:
  [0] # 员工报销管理制度 本制度自 2026 年 1 月 1 日起执行，适用于全体正式员工。
  [1] ## 住宿标准 总监级别每晚上限 800 元，经理级别 500 元，员工级别 350 元。
  [2] ## 审批流程 单次报销低于 1000 元由直属主管审批，高于 10000 元需分管副总审批。

元数据示例: {department=finance, source=manual-upload, parser=poi-xwpf,
             chunkStrategy=structured(max=800), sequence=0, totalChunks=3,
             documentTitle=员工报销管理制度.docx, documentVersion=v1}
```

一份 Word 文档，自动经历 `上传 → 解析 → 切分 → 入库`，每一步的状态推进、事件发布、元数据富化全部可追溯。

> 下一章预告：**第 6 章 · knowledge-embedding**，把 Chunk 变成向量，
> 并重点讲「Embedding 模型锁版本」这条铁律。

——回复「继续」进入第 6 章。
