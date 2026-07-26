# 第四章 四个 Agent 的完整实现：一条流水线的接力赛

> 本章目标：**逐行**剖析 PlannerAgent、ResearchAgent、WriterAgent、ReviewerAgent 四个具体智能体的完整实现，看它们如何在共享黑板上"接力"，把一句模糊需求变成一篇可发布的文章。

---

## 4.0 本章导读

第三章我们搭好了骨架（接口、抽象基类、黑板、可插拔 LLM）。骨架就像"空的舞台和规则"，但舞台上还没有演员。本章就是请出**四位主角**：

| 棒次 | Agent | 一句话职责 | 读黑板 | 写黑板 |
| --- | --- | --- | --- | --- |
| 第 1 棒 | **PlannerAgent** | 把模糊需求拆成大纲 | task.topic/requirement | OUTLINE |
| 第 2 棒 | **ResearchAgent** | 为每个小节收集素材 | OUTLINE | MATERIALS |
| 第 3 棒 | **WriterAgent** | 把大纲+素材写成正文 | OUTLINE, MATERIALS | DRAFT |
| 第 4 棒 | **ReviewerAgent** | 给草稿打分+提意见 | DRAFT | SCORE, REVIEW |

这就像一条真实的**内容生产流水线**：策划 → 调研 → 撰稿 → 审校。每一棒只干自己那份活（SRP），通过黑板把产出交给下一棒，谁也不用知道对方的内部细节（解耦）。

> 💡 **关键心智模型**：不要把四个 Agent 想成"四个函数被顺序调用"。要把它们想成**四个独立的员工**，各自守着黑板的"读区"和"写区"，靠黑板异步交接工作。这个心智模型能帮你理解为什么它们能被独立替换、独立测试。

---

## 4.1 第 1 棒：PlannerAgent 逐行剖析

### 4.1.1 类声明与依赖注入

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class PlannerAgent extends AbstractAgent {

    /** 可插拔大模型客户端（构造器注入，面向接口编程） */
    private final LlmClient llmClient;

    @Override
    public AgentRole role() {
        return AgentRole.PLANNER;
    }
```

**逐行解读**：

- `@Component`：交给 Spring 托管，这样它才能被 `AgentManager` 自动收集进花名册（呼应第三章 OCP）。
- `@RequiredArgsConstructor`：Lombok 为 `final` 字段生成构造器。这里生成了 `PlannerAgent(LlmClient)`，Spring 会自动把 `MockLlmClient` 注入进来。
- `private final LlmClient llmClient`：**依赖的是接口，不是实现**。这是 DIP 的关键一行——将来换成 `OpenAiLlmClient`，这行代码不用动。
- `extends AbstractAgent`：继承第三章的模板方法基类，自动获得"计时 + 日志 + 异常兜底"。
- `role()`：返回 `PLANNER`，这是它在花名册里的"身份证"。

### 4.1.2 核心业务 doExecute —— 五步走

```java
@Override
protected AgentResult doExecute(AgentContext context) {
    String topic = context.getTask().getTopic();
    String requirement = context.getTask().getRequirement();

    // 1) 构造提示词：systemPrompt 携带「规划」关键字，用于 MockLlmClient 分流
    String systemPrompt = "你是一名资深内容规划师，负责把用户需求拆解成清晰的写作大纲。"
            + "请只输出大纲，各小节之间用 ||| 分隔，不要输出多余解释。";
    String userPrompt = "主题：" + topic + "\n"
            + "写作要求：" + (requirement == null ? "无特殊要求" : requirement) + "\n"
            + "请输出 5 个左右的大纲小节。";

    // 2) 调用大模型
    String raw = llmClient.chat(systemPrompt, userPrompt);
    if (raw == null || raw.isBlank()) {
        return AgentResult.fail(role(), "LLM 返回空大纲");
    }

    // 3) 解析：按 ||| 拆分成小节列表（与 MockLlmClient.mockPlan 的约定对齐）
    List<String> outline = Arrays.stream(raw.split("\\|\\|\\|"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    if (outline.isEmpty()) {
        return AgentResult.fail(role(), "大纲解析后为空");
    }

    // 4) 写回黑板，供下游 Agent 使用
    context.getMemory().put(SharedMemory.Keys.OUTLINE, outline);
log.info("[Planner] 生成大纲 {} 个小节", outline.size());

    // 5) 返回结构化结果
    String output = "大纲(" + outline.size() + "节)：" + String.join(" / ", outline);
    return AgentResult.ok(role(), output);
}
```

**五步的设计意图**：

1. **读需求**：从 `task` 里拿 `topic` 和 `requirement`。注意它只读、不改 task——task 是"只读输入"。
2. **构造提示词**：`systemPrompt` 里刻意写了"规划"二字。为什么？因为 `MockLlmClient.chat()` 靠这个关键字分流到 `mockPlan()`（第三章 3.8 讲过）。这是"业务约定"在代码里的落地。同时约定"用 ||| 分隔"，这样解析才有依据。
3. **调 LLM + 空值防御**：拿到 `raw` 先判空。`raw == null || raw.isBlank()` 是防御式编程——不信任外部返回值。
4. **解析 + 二次防御**：用 Java Stream 三连（`split → trim → filter`）把原始字符串切成干净的小节列表。`\\|\\|\\|` 是正则转义后的 `|||`（`|` 在正则里是特殊字符，必须转义）。解析后再判空，双保险。
5. **写黑板 + 返回**：`put(Keys.OUTLINE, outline)` 把大纲挂到黑板上。**这一步是"接力交棒"的动作**——下游 ResearchAgent 就是从这个 key 读数据。最后返回 `AgentResult.ok(...)`，携带人类可读的摘要，方便审计日志展示。

> ⚠️ **易错点**：第 3 步的 `split("\\|\\|\\|")` 如果写成 `split("|||")`，会因为 `|` 是正则元字符而完全失效（切出一堆空字符。这是初学者最常踩的坑，务必记住"分隔符含正则特殊字符要转义"。

### 4.1.3 覆盖钩子方法定制日志

```java
@Override
protected String summarizeInput(AgentContext context) {
    return "规划主题=" + context.getTask().getTopic();
}
```

这就是第三章讲的"钩子方法"的实战。默认的`summarizeInput` 只输出 topic，Planner 覆盖它加上"规划主题="前缀，让审计日志更可读。**注意它没覆盖 `onError`**——因为 Planner 是第一棒，失败就该直接失败（没有可降级的上游产出）。

---

## 4.2 第 2 棒：ResearchAgent 逐行剖析

### 4.2.1 职责边界

ResearchAgent 的职责是"**为大纲的每个小节收集素材**"。它读 `OUTLINE`，写 `MATERIALS`。关键设计点在于**防御式读取上游产出**。

### 4.2.2 核心业务 doExecute

```java
@Override
@SuppressWarnings("unchecked")
protected AgentResult doExecute(AgentContext context) {
    SharedMemory memory = context.getMemory();

    // 1) 防御式读取上游产出：大纲必须存在
    List<String> outline = memory.get(SharedMemory.Keys.OUTLINE, List.class);
    if (outline == null || outline.isEmpty()) {
        return AgentResult.fail(role(), "缺少大纲(OUTLINE)，无法收集素材");
    }

    String topic = context.getTask().getTopic();
    String systemPrompt = "你是一名严谨的研究员，负责为写作大纲收集素材（事实、数据、案例）。"
            + "请围绕给定小节输出可用于写作的要点。";

    // 2) 逐节收集素材，用 LinkedHashMap 保持大纲顺序
    Map<String, String> materials = new LinkedHashMap<>();
    for (String section : outline) {
        String userPrompt = "主题：" + topic + "\n"
                + "当前小节：" + section + "\n"
                + "请为该小节收集 2-3 条可用素材。";
        String material = llmClient.chat(systemPrompt, userPrompt);
        materials.put(section, material == null ? "" : material.trim());
    }

    // 3) 写回黑板
    memory.put(SharedMemory.Keys.MATERIALS, materials);
    log.info("[Research] 为 {} 个小节收集素材完成", materials.size());

    // 4) 返回结果
    String output = "素材已收集，覆盖 " + materials.size() + " 个小节";
    return AgentResult.ok(role(), output);
}
```

**关键设计逐点解读**：

1. **防御式读取（第 1 步）**：`memory.get(Keys.OUTLINE, List.class)` 从黑板取大纲。如果为空——说明上游 Planner 没干成活——ResearchAgent **主动失败**并返回清晰原因 `"缺少大纲(OUTLINE)"`。这是第三章"避坑：防御式读取上游产出"的落地。它不会 NPE 崩溃，而是优雅返回失败，交给 Coordinator 决定后续。

2. **`LinkedHashMap` 保序（第 2 步）**：为什么不用普通 `HashMap`？因为 `HashMap` 不保证遍历顺序。而大纲小节是**有逻辑顺序的**（第一节、第二节……），素材必须按大纲顺序排列，否则 WriterAgent 写出来的文章会"章节乱序"。`LinkedHashMap` 保证插入顺序 = 遍历顺序，正好对齐大纲。**这是一个体现工程细致度的选择**。

3. **逐节循环调 LLM**：对每个小节单独调一次 LLM。这样每节的素材更聚焦。注意 `material == null ? "" : material.trim()`——即使某节 LLM 返回 null，也存空串而不是 null，避免下游取素材时 NPE。防御式编程贯穿始终。

4. **`@SuppressWarnings("unchecked")`**：因为 `get(key, List.class)` 返回 `List` 原生类型转 `List<String>` 会有泛型警告。这里我们明确知道类型安全（是自己写进去的），所以抑制警告。

### 4.2.3 覆盖 summarizeInput

```java
@Override
@SuppressWarnings("unchecked")
protected String summarizeInput(AgentContext context) {
    List<String> outline = context.getMemory().get(SharedMemory.Keys.OUTLINE, List.class);
    return "待研究小节数=" + (outline == null ? 0 : outline.size());
}
```

日志里记录"待研究小节数=5"，比默认的"topic=xxx"更能反映 Research 这一棒的输入规模。**注意 `outline == null ? 0` 的空值处理**——连打日志都不允许 NPE。

---

## 4.3 第 3 棒：WriterAgent 逐行剖析

### 4.3.1 职责边界与降级策略

WriterAgent 的职责是"**把大纲 + 素材组织成 Markdown 正文**"。它的关键设计是**分级依赖**：大纲是硬依赖（缺了没法写），素材是软依赖（缺了可降级）。

### 4.3.2 核心业务 doExecute

```java
@Override
@SuppressWarnings("unchecked")
protected AgentResult doExecute(AgentContext context) {
    SharedMemory memory = context.getMemory();

    // 1) 防御式读取：大纲必须存在
    List<String> outline = memory.get(SharedMemory.Keys.OUTLINE, List.class);
    if (outline == null || outline.isEmpty()) {
        return AgentResult.fail(role(), "缺少大纲(OUTLINE)，无法写作");
    }
    // 素材允许缺失（降级为仅凭大纲写作）
    Map<String, String> materials = memory.get(SharedMemory.Keys.MATERIALS, Map.class);

    String topic = context.getTask().getTopic();

    // 2) 拼装写作提示词：把大纲与素材整理进 userPrompt
    StringBuilder userPrompt = new StringBuilder();
    userPrompt.append("主题：").append(topic).append("\n");
    userPrompt.append("请根据以下大纲与素材写一篇结构完整的 Markdown 文章：\n");
    for (String section : outline) {
        userPrompt.append("## ").append(section).append("\n");
        if (materials != null && materials.get(section) != null) {
            userPrompt.append("素材：").append(materials.get(section)).append("\n");
        }
    }

    String systemPrompt = "你是一名专业的内容写作者，负责把大纲与素材组织成一篇流畅的 Markdown 正文。"
            + "要求结构清晰、语言通顺、可直接发布。";

    // 3) 调 LLM 成文
    String draft = llmClient.chat(systemPrompt, userPrompt.toString());
    if (draft == null || draft.isBlank()) {
        return AgentResult.fail(role(), "LLM 返回空草稿");
    }

    // 4) 写回黑板
    memory.put(SharedMemory.Keys.DRAFT, draft.trim());
    log.info("[Writer] 生成草稿，长度={} 字符", draft.length());

    // 5) 返回结果
    return AgentResult.ok(role(), "草稿已生成，长度 " + draft.length() + " 字符");
}
```

**降级策略是本棒最大的看点**：

1. **硬依赖 vs 软依赖（第 1 步）**：大纲 `outline` 缺失就直接 `fail`（硬依赖）；素材 `materials` 缺失**不失败**，只是变量为 null（软依赖）。这体现了工程判断力——**不是所有上游缺失都要终止流程**。没有素材，作者靠大纲也能硬写一篇（质量差点，但流程不断）。

2. **拼装提示词时的空值保护（第 2 步）**：`if (materials != null && materials.get(section) != null)`——双重判空。第一层判 materials 整个 Map 是否存在（降级场景下为 null），第二层判某个具体小节是否有素材。只有都存在才把素材拼进提示词。这就是"降级为仅凭大纲写作"的具体实现：materials 为 null 时，循环里只 append 大纲标题，不 append 素材。

3. **`StringBuilder` 而非字符串拼接**：循环里拼接大量文本，用 `StringBuilder` 避免每次 `+` 都创建新 String 对象。这是性能敏感场景的标准写法。

4. **`draft.trim()` 写黑板**：去掉首尾空白再存，保证草稿干净。

> 💡 **架构启示**：区分"硬依赖"和"软依赖"是设计健壮流水线的核心能力。硬依赖缺失 → 快速失败；软依赖缺失 → 降级继续。这个判断没有标准答案，取决于业务对"完整性"和"可用性"的权衡。

---

## 4.4 第 4 棒：ReviewerAgent 逐行剖析

### 4.4.1 职责边界与解析健壮性

ReviewerAgent 是收尾棒，职责是"**给草稿打分（0~1）+ 提意见**"。它的关键设计是**解析健壮性**——LLM 返回的 `分数|意见` 格式可能不规范，必须有兜底。

### 4.4.2 兜底默认分常量

```java
/** 分数解析失败时的兜底默认分 */
private static final double DEFAULT_SCORE = 0.6;
```

把兜底分数抽成命名常量，而不是散落在代码里的"魔法数字 0.6"。将来要调整默认分，只改一处。**可维护性的小细节**。

### 4.4.3 核心业务 doExecute

```java
@Override
protected AgentResult doExecute(AgentContext context) {
    SharedMemory memory = context.getMemory();

    // 1) 防御式读取：草稿必须存在
    String draft = memory.getString(SharedMemory.Keys.DRAFT);
    if (draft == null || draft.isBlank()) {
        return AgentResult.fail(role(), "缺少草稿(DRAFT)，无法评审");
    }

    String systemPrompt = "你是一名严格的内容评审专家，负责审校文章质量。"
            + "请先给出 0~1 的质量分数，再用 | 分隔给出评审意见，格式：分数|意见。";
    String userPrompt = "请评审以下文章：\n" + draft;

    // 2) 调 LLM 评审
    String raw = llmClient.chat(systemPrompt, userPrompt);
    if (raw == null || raw.isBlank()) {
        return AgentResult.fail(role(), "LLM 返回空评审");
    }

    // 3) 解析「分数|意见」（与 MockLlmClient.mockReview 的约定对齐）
    double score = DEFAULT_SCORE;
    String review = raw.trim();
    int sep = raw.indexOf('|');
    if (sep > 0) {
        String scorePart = raw.substring(0, sep).trim();
        review = raw.substring(sep + 1).trim();
       try {
            score = Double.parseDouble(scorePart);
            // 约束在 [0,1] 区间
            score = Math.max(0.0, Math.min(1.0, score));
        } catch (NumberFormatException e) {
            log.warn("[Reviewer] 分数解析失败：{}，降级为默认分 {}", scorePart, DEFAULT_SCORE);
            score = DEFAULT_SCORE;
        }
    }

    // 4) 写回黑板
    memory.put(SharedMemory.Keys.SCORE, score);
    memory.put(SharedMemory.Keys.REVIEW, review);
    log.info("[Reviewer]评审完成，分数={}", score);

    // 5) 返回结果
    return AgentResult.ok(role(), "评审分数=" + score + "，意见：" + review);
}
```

**三层解析防御（第 3 步是精华）**：

1. **默认值兜底**：`double score = DEFAULT_SCORE` 一开始就给分数赋默认值 0.6。这样无论后面解析成功与否，score 都有合法值。
2. **格式校验 `sep > 0`**：`indexOf('|')` 找分隔符位置。`> 0`（而非 `>= 0`）保证分隔符前面**有内容**（分数部分非空）。找不到分隔符就保持默认分，review 保持整段原文。
3. **解析异常捕获 + 区间约束**：`Double.parseDouble` 可能抛 `NumberFormatException`（LLM 返回了"高分"这种非数字），用 try-catch 兜住降级为 0.6。即使解析成功，也用 `Math.max(0, Math.min(1, score))` 把分数**钳制在 [0,1] 区间**——防止 LLM 返回 "1.5" 这种越界值。

这三层防御叠加，保证**无论 LLM 返回什么鬼东西，ReviewerAgent 都能给出一个合法的分数**，绝不崩溃。这是"不信任外部输入"原则的教科书级示范。

> ⚠️ **注意 `memory.getString(...)`**：Reviewer 读草稿用的是 `getString` 而非 `get(key, String.class)`。这是 SharedMemory 提供的便捷方法（第五章会详解黑板的完整 API）。

---

## 4.5 四棒接力全景：数据如何在黑板上流动

把四棒串起来看，黑板上的数据是这样逐步"生长"的：

```
初始：  黑板 = { }                                    task = {topic:"AI工具推荐", requirement:"面向新手"}

Planner 执行后：
        黑板 = { OUTLINE: ["工具概览","效率工具","写作工具","编程工具","总结推荐"] }

Research 执行后：
        黑板 = { OUTLINE: [...],
                 MATERIALS: {"工具概览":"素材A","效率工具":"素材B", ...} }

Writer 执行后：
        黑板 = { OUTLINE: [...], MATERIALS: {...},
                 DRAFT: "# AI工具推荐\n## 工具概览\n..." }

Reviewer 执行后：
        黑板 = { OUTLINE:[...], MATERIALS:{...}, DRAFT:"...",
                 SCORE: 0.85, REVIEW: "结构清晰，建议补充案例" }
```

**观察三个关键点**：

1. **黑板只增不减**：每一棒往黑板上"添"数据，从不删除上游的产出。这样任何一棒都能回看之前所有环节的产物（可追溯）。
2. **各棒的读写区严格不重叠冲突**：Planner 只写 OUTLINE，Research 只写 MATERIALS……没有两个 Agent 写同一个 key。这避免了"谁覆盖谁"的竞态问题。
3. **数据格式在 Keys 常量里约定死**：OUTLINE 一定是 `List<String>`，MATERIALS 一定是 `Map<String,String>`。四个 Agent 严格遵守，这就是黑板协作的"通信协议"。

用流程图表示四棒接力：

```mermaid
graph LR
    Task[用户任务 Task] --> Planner[PlannerAgent 规划]
    Planner -->|写 OUTLINE| Board1[黑板]
    Board1 -->|读 OUTLINE| Research[ResearchAgent 研究]
    Research -->|写 MATERIALS| Board2[黑板]
    Board2 -->|读 OUTLINE+MATERIALS| Writer[WriterAgent 写作]
    Writer -->|写 DRAFT| Board3[黑板]
    Board3 -->|读 DRAFT| Reviewer[ReviewerAgent 评审]
    Reviewer -->|写 SCORE+REVIEW| Result[最终产出]
```

---

## 4.6 企业案例：为什么"逐节调 LLM"而不是"一次调完"

某电商内容团队最初让 WriterAgent"一次性把整篇文章生成完"，结果发现：文章越长，LLM 越容易"跑题""前后矛盾""格式崩坏"。后来他们改成本章 ResearchAgent 的做法——**逐节收集、逐节处理**，质量显著提升。

**为什么分而治之更好？**

| 维度 | 一次性生成 | 逐节生成 |
| --- | --- | --- |
| 单次上下文长度 | 长，易超限/跑题 | 短，聚焦 |
| 失败影响 | 整篇重来 | 只重做单节 |
| 可并行性 | 无 | 各节可并行（未来优化点） |
| 质量可控性 | 差 | 好（每节可单独校验） |

**结论**：Multi-Agent 的"分工"思想不仅体现在 Agent 之间，也体现在**单个 Agent 内部的任务拆分**上。ResearchAgent 的逐节循环就是这个思想的微观体现。

---

## 4.7 常见问题 FAQ

**Q1：四个 Agent 之间为什么不直接互相调用方法，非要通过黑板？**
A：直接调用会产生"编译期强耦合"——WriterAgent 得 import ResearchAgent，改一个动一串。通过黑板，Agent 之间只依赖"数据约定"（Keys 常量），不依赖彼此的类。这是解耦的核心手段。

**Q2：ResearchAgent 逐节调 LLM，会不会很慢？**
A：串行确实慢（N 节调 N 次）。V1 优先保证正确和清晰。未来优化方向是"并行调用"——用线程池并发处理各节，这也是 SharedMemory 用 ConcurrentHashMap 的伏笔。

**Q3：WriterAgent 素材缺失时降级写作，质量会不会没保障？**
A：会打折，但保证了"可用性"。这是"降级 > 不可用"的工程权衡。而且下游 ReviewerAgent 会给低分，Coordinator 可据此触发返工（进阶策略）。

**Q4：ReviewerAgent 的分数解析为什么要三层防御，是不是过度设计？**
A：不是。LLM 的输出**天然不可控**——它可能返回"评分：0.8"、"高分"、"1.5"各种奇葩格式。面对不可控输入，防御的成本远低于线上崩溃的代价。这是"面向失败设计"。

---

## 4.8 面试高频题

1. **描述一下你实现的多 Agent 流水线，数据是如何流转的？**
   （参考答案：四个 Agent 通过共享黑板 SharedMemory 接力，Planner 写 OUTLINE → Research 读 OUTLINE 写 MATERIALS → Writer 读两者写 DRAFT → Reviewer 读 DRAFT 写 SCORE/REVIEW，黑板只增不减、可追溯。）

2. **如何保证单个 Agent 处理外部（LLM）返回值时不崩溃？**
   （参考答案：空值判断 + 格式校验 + 异常捕获 + 默认值兜底 + 区间约束，以 ReviewerAgent 的分数解析为例展开。）

3. **HashMap 和 LinkedHashMap 的区别？你在项目里哪里用了后者，为什么？**
   （参考答案：LinkedHashMap 保证插入顺序=遍历顺序。ResearchAgent 用它保证素材顺序对齐大纲顺序，避免文章章节乱序。）

4. **什么是硬依赖和软依赖？举例说明如何处理。**
   （参考答案：WriterAgent 中大纲是硬依赖缺失即失败，素材是软依赖缺失可降级写作。）

---

## 4.9 本章练习（含参考答案）

**练习 1**：PlannerAgent 里 `split("\\|\\|\\|")` 的双反斜杠是什么意思？如果改成 `split("|||")` 会发生什么？

<details><summary>参考答案</summary>

`\\|` 在 Java 字符串里表示正则的 `\|`，即转义的 `|`（匹配字面量竖线）。若写 `split("|||")`，`|` 是正则"或"元字符，`|||` 相当于"空或空或空"，会把字符串按每个字符位置切开，得到一堆空串——解析完全失效。
</details>

**练习 2**：为 WriterAgent 覆盖 `onError`，实现"写作失败时，把大纲原样作为占位草稿写入 DRAFT"的降级。

<details><summary>参考答案</summary>

```java
@Override
@SuppressWarnings("unchecked")
protected AgentResult onError(AgentContext ctx, Exception e) {
    List<String> outline = ctx.getMemory().get(SharedMemory.Keys.OUTLINE, List.class);
    String placeholder = outline == null ? "（无内容）"
            : outline.stream().map(s -> "## " + s).collect(Collectors.joining("\n"));
    ctx.getMemory().put(SharedMemory.Keys.DRAFT, placeholder);
    return AgentResult.ok(role(), "写作异常，已降级为大纲占位草稿");
}
```
</details>

**练习 3**：如果 ReviewerAgent 打分低于 0.6，你希望流水线"退回 WriterAgent 重写"。这个逻辑应该放在哪个类里？为什么？

<details><summary>参考答案</summary>

应放在 **Coordinator**（第六章详解），而不是 ReviewerAgent。因为"是否返工、退回哪一步"属于**流程调度决策**，是 Coordinator 的职责（SRP）。ReviewerAgent 只负责"打分+提意见"，不该关心流程走向。
</details>

---

## 4.10 本章任务

> ✅ **动手清单**（对应代码已在 `day08multiagent/agent/` 各子目录）

1. 阅读 `agent/planner/PlannerAgent.java`，找出"五步走"和 `||| ` 解析逻辑。
2. 阅读 `agent/research/ResearchAgent.java`，理解为什么用 `LinkedHashMap` 而非 `HashMap`。
3. 阅读 `agent/writer/WriterAgent.java`，区分大纲（硬依赖）和素材（软依赖）的不同处理。
4. 阅读 `agent/reviewer/ReviewerAgent.java`，数清分数解析的三层防御。
5. 打开 `config/MockLlmClient.java`，对照四个 Agent 的 systemPrompt 关键字，验证"分流约定"是否一一对齐。
6. **挑战题**：完成练习 2，为 WriterAgent 加降级逻辑，用 `mvn compile` 验证编译通过。

**下一章预告**：第五章我们将深入**共享 Memory（黑板）**的完整设计与 API，剖析它如何在保证灵活性的同时兼顾类型安全，以及为并行化预留的能力。