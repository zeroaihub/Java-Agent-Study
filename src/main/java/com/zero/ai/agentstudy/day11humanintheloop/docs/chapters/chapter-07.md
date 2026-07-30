# Chapter 07 · Feedback Engine：人类反馈与反馈学习（让 Agent 越用越聪明）

> 本章隶属 Day11 Human-in-the-loop 模块。前六章我们把「人能不能拦住 Agent、能不能改它、能不能多级会签、没人批怎么办」全打通了——这些解决的是**安全**问题。本章要解决的是另一个维度的问题：**成长**。人拦下来、纠正完之后，这份宝贵的人类智慧，能不能被沉淀、被复用、让 Agent 下次不再犯同样的错？这就是 Feedback Engine + Feedback Learning 要干的事。

---

## 一、为什么要学（Why）

### 1.1 翻车现场：同一个错，Agent 犯了 47 次

某金融客服团队上线了一个 AI 投顾助手。第一周，人工质检抽查发现一个问题：只要用户问「XX 基金能买吗」，Agent 就直接回「可以买，收益不错」——**完全没有加风险提示与免责声明**，这是合规红线。

质检员当场就在后台点了「否定」，还手写了正确答案：「任何基金都有风险，本回答不构成投资建议，请根据自身风险承受能力谨慎决策……」。

问题来了：

- 这位质检员纠正了 3 条,第二天，Agent 遇到相似问题，**照样不加免责声明**。
- 一周后统计，同一类「缺免责声明」的错误，Agent 一共犯了 **47 次**，质检员纠正了 47 次。
- 每一次纠正都被「记录」进了数据库，但**没有任何一条被喂回给 Agent**。人的智慧，进了数据库的坟墓。

这就是**只收集、不学习**的典型翻车：反馈成了一次性消耗品，Agent 永远长不大。

### 1.2 反馈的两种归宿

人给 Agent 的反馈，只有两种归宿：

| 归宿 | 结果 | 本章立场 |
|------|------|----------|
| 记一笔就丢 | Agent 原地踏步，同错反复犯，人力被反复消耗 | ✗ 要避免 |
| 沉淀成经验反哺 | Agent 越用越准，人力投入随时间递减 | ✓ 要实现 |

一个没有反馈闭环的 Agent，本质上是个「永远长不大的实习生」——你教一百遍，它还是第一天的水平。而**反馈闭环**，就是把「人教它」变成「它记住」。

### 1.3 本章要回答的三个核心问题

| 核心问题 | 对应组件 | 一句话 |
|----------|----------|--------|
| 人的反馈怎么被安全地收进来？ | `FeedbackEngine` + `HumanFeedback` | 结构化建模，四种反馈类型，收集即记账 |
| 收进来的反馈怎么变成 Agent 能用的经验？ | `FeedbackLearningService` + `FewShotExample` | 从纠正里提炼 few-shot，沉淀进知识库 |
| 经验怎么反哺给下一次执行？ | `buildFewShotBlock()` 注入 Prompt | 召回 top-N 示例，拼进 Prompt 引导模型 |

---

## 二、是什么（What）

### 2.1 复用前几章的基础设施

本章不重复造轮子。我们复用：

- `AgentAction`（ch02）：反馈总要归属到某次 Agent 动作，`taskId` / `type` 是天然关联键。
- 六边形架构 + Repository 模式（ch03）：反馈也走「接口 + 内存实现」，生产可换 PostgreSQL / 向量库。
- 不可变 record + 防御性拷贝（贯穿全程）：反馈是「已发生的事实」，必须不可变。

### 2.2 本章新增的六个类

| 类 | 类型 | 职责 |
|----|------|------|
| `FeedbackType` | 枚举 | 四种反馈类型：认可 / 否定 / 纠正 / 建议 |
| `HumanFeedback` | record 值对象 | 一条反馈的完整记录（谁、对哪次产出、什么反馈、内容、评分） |
| `FeedbackRepository` | 接口（出站端口） | 反馈持久化契约 |
| `InMemoryFeedbackRepository` | 实现 | 内存版仓储（教学 / 单测） |
| `FeedbackEngine` | 编排器（入站服务） | 反馈收集入口 + 基本统计 |
| `FewShotExample` | record 值对象 | 学习产物：可注入 Prompt 的经验单元 |
| `FeedbackLearningService` | 学习服务 | 提炼 → 沉淀 → 召回 → 注入 的完整学习闭环 |

### 2.3 协作全景图

```
          人（质检员 / 用户）
                │ 点赞 / 点踩 / 纠正 / 建议
                ▼
        ┌───────────────────┐
        │   FeedbackEngine   │  收集入口（快、幂等、可审计）
        └─────────┬─────────┘
                  │ save
                  ▼
        ┌───────────────────┐
        │ FeedbackRepository │  持久化（内存 / PG / 向量库）
        └─────────┬─────────┘
                  │ findByType(CORRECTION)
                  ▼
        ┌────────────────────────┐
        │ FeedbackLearningService │  学习（可异步 / 批处理）
        │  ①提炼 ②沉淀 ③召回 ④注入 │
        └─────────┬──────────────┘
                  │ buildFewShotBlock(taskType)
                  ▼
        ┌───────────────────┐
        │   下一次 Agent 执行  │  Prompt += few-shot 经验块
        └───────────────────┘
```

一句话：**收集与学习分离**——`FeedbackEngine` 管收进来（在线、要快），`FeedbackLearningService` 管学出去（可离线、可批量）。

---

## 三、怎么用（How）——逐类精讲

### 3.1 `FeedbackType`：把反馈类型收敛成受控枚举

为什么用枚举而不是字符串？因为反馈类型是「业务语义高度稳定」的领域概念，用枚举可获得编译期穷尽性检查、EnumMap 高性能、序列化友好。四种类型各有用途：

```java
APPROVE_RATING("认可", true)   // 点赞 → 可作正例强化
REJECT_RATING("否定", false)   // 点踩 → 负例，回流复核
CORRECTION("纠正", false)      // 纠正 → 自带 ground truth，few-shot 首选来源
SUGGESTION("建议", true)       // 建议 → 软知识 / 提示词补充
```

关键方法 `carriesGroundTruth()`：只有 `CORRECTION` 返回 true——因为只有「纠正」带来了明确的**正确答案**，这是能直接学的东西。

### 3.2 `HumanFeedback`：一条反馈的完整快照

核心是「不可变 + 强校验」。看紧凑构造器里两条硬约束：

```java
if (score != null && (score < MIN_SCORE || score > MAX_SCORE)) {
    throw new IllegalArgumentException("score 必须在 [1, 5] 之间…");
}
// CORRECTION 必须带出「正确内容」，否则这条纠正没有可学习的价值
if (type == FeedbackType.CORRECTION && (content == null || content.isBlank())) {
    throw new IllegalArgumentException("CORRECTION 类型的反馈必须提供 content（期望的正确产出）");
}
```

第二条是**领域约束前置**的典范：一条「纠正」如果不带正确答案，它就是废数据——与其让它流到学习层再报错，不如在入口就拒绝。

四个语义化工厂让业务方零心智负担：

```java
HumanFeedback.approve(taskId, output, "张三", 5);          // 点赞并打 5 分
HumanFeedback.reject(taskId, output, "李四", "缺免责声明");  // 点踩附原因
HumanFeedback.correct(taskId, output, 正确答案, "王五");     // 纠正给标准答案
HumanFeedback.suggest(taskId, output, "回答要加风险提示", "赵六"); // 建议
```

`learnableOutput()` 是学习层的入口钩子：CORRECTION 返回人给的正确答案，其他类型返回 null。

### 3.3 `FeedbackRepository` + `InMemoryFeedbackRepository`：为什么反馈要单独存

反馈**不塞进 ApprovalRequest**，因为二者生命周期完全不同：审批是「一次性决策」，反馈是「持续积累的语料」，会被离线训练、相似检索、报表反复消费。职责不同，存储分离。

仓储提供 `findByType(CORRECTION)`——这是学习层批量提炼的关键入口。内存实现所有查询都返回**新列表快照**，防止调用方误改内部状态。

### 3.4 `FeedbackEngine`：收集与统计的入口

只干两件事：**收进来**（`submit` / `approve` / `reject` / `correct` / `suggest`）+ **给统计**。看三个生产级监控指标：

```java
positiveRatio(taskId)   // 正向反馈占比 → 骤降就告警
averageScore(taskId)    // 平均评分 → 质量趋势
typeDistribution()      // 各类型分布 → 用 EnumMap 保证顺序稳定
```

`positiveRatio` 是最直观的「Agent 表现好不好」指标，生产里常做成实时看板。

### 3.5 `FewShotExample` + `FeedbackLearningService`：学习闭环的灵魂

`FewShotExample` 是学习**产物**：把一条反馈提炼成「输入侧 + 期望输出侧」的样例对，将来拼进 Prompt。`toPromptSnippet()` 渲染成可注入文本。

`FeedbackLearningService` 是四步闭环：

```java
// ① 提炼：单条在线学 or 全量批处理
learnFrom(taskType, feedback);   // 增量：一条纠正 → 一条示例
learnAll();                       // 批处理：扫全部 CORRECTION（定时任务典型入口）

// ② 沉淀：按 taskType 归档进知识库，同 ID 覆盖保证幂等
store(example);

// ③ 召回：按权重降序取 top-N（示例太多会挤占上下文）
recall(taskType, topN);

// ④ 注入：渲染成经验块，拼进 Prompt
buildFewShotBlock(taskType);
```

**为什么先做 In-Context Learning 而不是微调模型？** 因为微调成本高、周期长、需 GPU 与 MLOps；而提示词学习「零训练、即时生效、可解释、可撤回」，是绝大多数企业 Agent 落地反馈闭环的**第一选择**。等示例积累够多，再考虑离线蒸馏 / 微调。

---

## 四、用在哪（真实项目）

### 4.1 三类真实落地场景

| 场景 | 反馈来源 | 学习方式 | 收益 |
|------|----------|----------|------|
| AI 客服 / 投顾 | 质检员纠正合规问题 | CORRECTION → few-shot 注入 | 同类合规错误从 47 次/周降到 0 |
| 代码助手 | 开发者点赞/点踩生成的代码 | 正向率看板 + 负例回流 | 定位「哪类需求生成质量差」 |
| 内容审核 Agent | 审核员纠正误判 | CORRECTION → 提示词补充规则 | 边界 case 越积越准 |

### 4.2 端到端 Demo：从「犯错」到「学会」

回到 1.1 的投顾场景，完整演示反馈闭环怎么让 Agent 学会加免责声明：

```java
// ===== 装配 =====
FeedbackRepository repo = new InMemoryFeedbackRepository();
FeedbackEngine engine = new FeedbackEngine(repo);
FeedbackLearningService learning = new FeedbackLearningService(repo);

String taskType = "FUND_CONSULT"; // 基金咨询任务类型

// ===== 第 1 步：Agent 犯错，质检员纠正 =====
String wrongOutput = "这只基金可以买，收益不错。";
String correctOutput = "任何基金都有风险，本回答不构成投资建议，请根据自身风险承受能力谨慎决策。";
HumanFeedback fb = HumanFeedback.correct("task-001", wrongOutput, correctOutput, "质检员-张三");
// 附上 taskType，便于学习层按场景归档
HumanFeedback fbWithType = new HumanFeedback(
        fb.feedbackId(), fb.taskId(), fb.targetOutput(), fb.type(),
        fb.content(), fb.score(), fb.reviewer(),
        java.util.Map.of("taskType", taskType), fb.createdAt());
engine.submit(fbWithType);

// ===== 第 2 步：学习——从纠正提炼 few-shot =====
learning.learnAll();  // 扫描所有 CORRECTION，提炼入知识库
System.out.println("已学到示例数：" + learning.exampleCount(taskType)); // 1

// ===== 第 3 步：下一次执行，注入经验 =====
String fewShotBlock = learning.buildFewShotBlock(taskType);
String systemPrompt = "你是一名专业投顾助手。\n";
String userQuestion = "用户问：YY 基金能买吗？";
String finalPrompt = systemPrompt + fewShotBlock + userQuestion;

System.out.println("=== 注入经验后的最终 Prompt ===");
System.out.println(finalPrompt);
```

控制台输出（关键部分）：

```
已学到示例数：1
=== 注入经验后的最终 Prompt ===
你是一名专业投顾助手。
以下是过往人工纠正沉淀的正确示例，请参照其风格与标准作答：
【示例】
场景/输入：这只基金可以买，收益不错。
正确产出：任何基金都有风险，本回答不构成投资建议，请根据自身风险承受能力谨慎决策。
用户问：YY 基金能买吗？
```

**关键洞察**：从第 3 步起，模型每次回答基金问题时，Prompt 里都带着「人教过的正确样子」，缺免责声明的错误自然大幅下降——**人只教了一次，Agent 记住了**。

### 4.3 反馈学习执行时序

```
质检员      FeedbackEngine   FeedbackRepository   FeedbackLearningService   下次执行
  │  correct()    │                 │                      │                  │
  ├──────────────>│  save()         │                      │                  │
  │               ├────────────────>│                      │                  │
  │               │                 │  (定时/触发) learnAll()│                  │
  │               │                 │<─────────────────────┤                  │
  │               │                 │  findByType(CORRECTION)                  │
  │               │                 ├─────────────────────>│ 提炼 FewShotExample│
  │               │                 │                      │ store 进知识库    │
  │               │                 │                      │                  │
  │               │                 │       buildFewShotBlock(taskType)        │
  │               │                 │                      │<─────────────────┤
  │               │                 │                      │─经验块─────────> │ Prompt 注入
```

### 4.4 生产接法：定时批处理学习

在线收集要快，学习可以异步批跑。Spring 里用 `@Scheduled`：

```java
@Component
public class FeedbackLearningJob {
    private final FeedbackLearningService learning;

    public FeedbackLearningJob(FeedbackLearningService learning) {
        this.learning = learning;
    }

    // 每小时批量学习一次：把新积累的纠正反馈固化成经验
    @Scheduled(cron = "0 0 * * * ?")
    public void learn() {
        int n = learning.learnAll();
        // learnAll 幂等（示例 ID 派生自反馈 ID），重复跑不会产生脏数据
        log.info("本轮反馈学习完成，处理示例 {} 条", n);
    }
}
```

**为什么学习不放在 `submit` 里同步做？** 因为提炼/向量化可能耗时，若同步执行会拖慢在线提交反馈的接口响应；且批处理能做去重、聚合、权重重算等更复杂的加工。**收集与学习解耦**，各自按最优节奏跑。

---

## 五、避坑指南（≥10 条）+ 小结 + FAQ + 面试题

### 5.1 避坑清单

1. **只收集不学习**（本章头号坑）：反馈进了库就躺尸，Agent 永远长不大。一定要闭环。
2. **CORRECTION 不带正确答案**：一条纠正没有 ground truth 就是废数据，务必在入口校验（本模块已在 `HumanFeedback` 构造器强制）。
3. **反馈可变**：反馈是「已发生的事实」，必须不可变（record）。允许事后改反馈 = 审计/训练数据不可信。
4. **收集与学习混在一起**：同步学习会拖慢在线接口。务必分离，学习走异步/批处理。
5. **few-shot 示例注入过多**：示例越多越好是错觉——挤占上下文、稀释重点、增加 token 成本。默认 top-3，按权重召回。
6. **taskType 缺失导致召回错乱**：示例按 taskType 归档，若不带 taskType 会全落到 taskId 桶里，召回不精准。生产务必从 `AgentAction.type` 带过来。
7. **学习不幂等**：批处理重复跑产生重复示例。本模块示例 ID 派生自反馈 ID + 同 ID 覆盖，天然幂等。
8. **把 AUTO 学习无人工复核直接上线**：机器学到的经验也可能是错的（比如人给的纠正本身就错）。高危场景应有「示例上线前人工审核」环节。
9. **正向率骤降不告警**：`positiveRatio` 是最灵敏的质量哨兵，务必接监控告警，而不是等用户投诉。
10. **评分越界不校验**：score 应约束在合法区间（本模块强制 [1,5]），否则统计口径崩坏。
11. **反馈存 ApprovalRequest 里**：审批与反馈生命周期/查询维度完全不同，务必分表分仓储。
12. **忽略负例价值**：点踩样本不是垃圾——可用于告警、回流复核、训练时降权，别直接丢。
13. **示例无来源可追溯**：`FewShotExample.sourceFeedbackId` 必须保留，否则出问题无法定位/撤回某条经验。

### 5.2 小结

本章把 HITL 从「安全」推进到「成长」：

- **收集**：`FeedbackEngine` + `HumanFeedback` + 四种 `FeedbackType`，结构化、不可变、强校验。
- **学习**：`FeedbackLearningService` 四步闭环——提炼（从 CORRECTION）→ 沉淀（按 taskType 归档）→ 召回（top-N 权重）→ 注入（`buildFewShotBlock` 拼 Prompt）。
- **产物**：`FewShotExample` 是可复用、可追溯、可版本化的经验单元。
- **核心思想**：In-Context Learning 优先于微调；收集与学习解耦；一切经验可追溯可撤回。

至此，Agent 拥有了「越用越聪明」的能力——人教一次，它记一生。

### 5.3 FAQ

**Q1：为什么只从 CORRECTION 学，点赞（APPROVE）不学吗？**
A：点赞的产出也是好正例，但它「本来就对」，学习收益低于纠正（把错的变对）。教学期聚焦 CORRECTION 这条 ROI 最高的路径；生产可扩展：把高分点赞样本也纳入正例库。

**Q2：In-Context Learning 和微调（Fine-tuning）怎么选？**
A：先用 In-Context Learning（零训练、即时生效、可撤回），示例积累到成百上千且模式稳定后，再考虑离线微调把经验「烧进」模型权重。二者不互斥，是演进关系。

**Q3：内存知识库重启就没了，怎么办？**
A：教学期用内存 Map，生产必须持久化——首选**向量库**（按语义相似度召回，比 taskType 精确匹配更智能），替换 `FeedbackLearningService` 里的 `knowledgeBase` 即可，上层 API 不变。

**Q4：召回为什么按 taskType 而不是语义相似度？**
A：教学期为降低依赖，用 taskType 精确匹配做「够用」的召回。生产强烈建议升级为向量语义召回：把 input 向量化，按余弦相似度取 top-N，能召回「语义相似但字面不同」的经验。

**Q5：怎么防止学到「错误的经验」？**
A：三道防线——①入口校验（CORRECTION 必带正确内容）；②示例上线前人工审核（高危场景）；③保留 `sourceFeedbackId` 可追溯撤回。机器学到的东西也要「可回滚」。

### 5.4 面试题

1. 什么是 Human Feedback / Feedback Learning？它解决 Agent 的什么本质问题？
2. 为什么反馈要单独建模、单独持久化，而不塞进审批请求里？
3. 四种反馈类型各自的用途是什么？为什么只有 CORRECTION「自带 ground truth」？
4. In-Context Learning（提示词学习）与模型微调各有什么优劣？企业落地为什么通常先选前者？
5. 为什么「收集」与「学习」要职责分离？分离带来什么好处？
6. few-shot 示例注入 Prompt 时，为什么要限制 top-N？太多会有什么问题？
7. 如何保证批处理学习的幂等性？本模块用了什么手段？
8. 生产环境如何把 taskType 精确召回升级为语义相似度召回？需要引入什么基础设施？

---

> 下一章预告 · **Chapter 08 · Approval API + UI**：前七章我们把 HITL 的「引擎」全造好了——审批、中断、恢复、检查点、多级会签、反馈学习。但这些引擎都藏在后端，人怎么「够得着」它们？下一章我们把这些能力暴露成 REST API + 一个极简审批控制台，让审批人真正能在界面上「点通过 / 点拒绝 / 写纠正」，把 HITL 从代码变成产品。