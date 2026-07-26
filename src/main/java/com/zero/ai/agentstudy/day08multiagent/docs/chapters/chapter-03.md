# 第三章：用 SOLID 原则设计 Multi-Agent 框架骨架

> 本章目标：把第二章讲的「六大件」架构，用 Java 的 SOLID 原则一根一根地立起来。学完你将拥有一套可编译、可扩展、可替换的 Multi-Agent 框架骨架，理解每一个接口、每一个抽象类"为什么这么设计"。

---

## 3.0 本章导读：从"能跑"到"能维护"

第二章我们画出了 Multi-Agent 的架构图，也用伪代码跑通了流程。但那只是"能跑"。真正的企业级系统，考验的是"三个月后还能不能改得动"。

设想一个真实场景：你的 Multi-Agent 内容平台上线了，跑得好好的。三个月后产品经理提了三个需求：

1. "我们要接入公司自研的大模型，不再用 OpenAI 了。"
2. "加一个『翻译 Agent』，把文章翻译成英文。"
3. "评审逻辑要改，分数低于 0.8 的要自动打回给写作 Agent 重写。"

如果你的代码是"一坨"——所有逻辑塞在一个 `MultiAgentService` 里，`if role == "planner"` 一路 `else if` 到底，那么这三个需求每一个都要动那坨代码，改一处崩三处。

而如果你一开始就按 SOLID 设计：

1. 换模型 → 写一个新的 `LlmClient` 实现，改一行配置。**原有代码零改动。**
2. 加翻译 Agent → 新建一个 `TranslatorAgent extends AbstractAgent`。**原有代码零改动。**
3. 改评审逻辑 → 只改 `Coordinator` 的调度循环。**Agent 代码零改动。**

这就是 SOLID 的威力：**让变化被隔离在最小的范围内。** 本章就是带你把这套"抗变化"的骨架亲手搭出来。

---

## 3.1 SOLID 五原则速通（用 Multi-Agent 举例）

SOLID 是五个设计原则的首字母缩写。很多教程讲得很抽象，我们这里全部用本项目的真实代码来讲，保证你一看就懂。

### 3.1.1 SRP —— 单一职责原则（Single Responsibility）

> **一个类只做一件事，只有一个"改变它"的理由。**

反例（一个类干四件事）：

```java
// ❌ 反面教材：上帝类
public class ContentGenerator {
    public String generate(String topic) {
        // 1. 规划大纲
        List<String> outline = ...;
        // 2. 收集素材
        Map<String, String> materials = ...;
        // 3. 写正文
        String draft = ...;
        // 4. 评审打分
        double score = ...;
        return draft;
    }
}
```

这个类有 **四个** 会改变它的理由：大纲逻辑变、素材逻辑变、写作逻辑变、评审逻辑变。任何一个变了都要动这个类，风险极高。

正例（拆成四个 Agent，各管一件事）：

- `PlannerAgent` → 只管规划大纲（改变理由：规划逻辑变）
- `ResearchAgent` → 只管收集素材（改变理由：素材逻辑变）
- `WriterAgent` → 只管写正文（改变理由：写作逻辑变）
- `ReviewerAgent` → 只管评审（改变理由：评审逻辑变）

**本项目落地**：每个 `XxxAgent` 类都只覆写一个 `doExecute()`，只干自己那档子事，绝不越界。

### 3.1.2 OCP —— 开闭原则（Open-Closed）

> **对扩展开放，对修改关闭。加新功能靠"新增代码"，而不是"修改老代码"。**

本项目落地：`AgentManager` 用 Spring 自动收集所有 `Agent` 实现。你想加一个翻译 Agent？只需：

```java
@Component
public class TranslatorAgent extends AbstractAgent {
    @Override public AgentRole role() { return AgentRole.TRANSLATOR; }
    @Override protected AgentResult doExecute(AgentContext ctx) { ... }
}
```

`AgentManager` 一行不改就自动收录它。这就是"对扩展开放（能加）、对修改关闭（不用改老的）"。

### 3.1.3 LSP —— 里氏替换原则（Liskov Substitution）

> **子类必须能无差别地替换父类，不破坏原有契约。**

本项目落地：`Coordinator` 拿到的永远是 `Agent` 接口。无论背后是 `PlannerAgent` 还是 `WriterAgent`，它都一视同仁地调 `agent.execute(context)`，拿回一个 `AgentResult`。任何一个 Agent 都能被放进流水线的任意位置，只要它遵守"输入 Context、输出 Result、不抛异常"这个契约。

### 3.1.4 ISP —— 接口隔离原则（Interface Segregation）

> **接口要小而专，不要逼实现类去实现用不到的方法。**

本项目落地：`Agent` 接口只有两个方法——`role()` 和 `execute()`。没有 `plan()`、`research()`、`write()` 这种把所有能力堆在一起的"胖接口"。每个实现只需关心这两个最小契约。

### 3.1.5 DIP—— 依赖倒置原则（Dependency Inversion）

> **高层模块不依赖低层模块，两者都依赖抽象。**

本项目落地：

- `Coordinator`（高层）不依赖 `PlannerAgent`（低层具体类），而是依赖 `Agent` 接口（抽象）。
- 每个 Agent（高层业务）不依赖 `OpenAiClient`（低层实现），而是依赖 `LlmClient` 接口（抽象）。

于是"换模型"这种低层变化，永远传导不到高层业务代码。

---

## 3.2 框架骨架全景图

我们把第二章的架构图，翻译成一组 Java 类型。下面是它们的依赖关系（箭头表示"依赖/调用"）：

```
                     ┌──────────────────┐
   HTTP 请求  ─────▶ │ ContentController │  (Web 层，只管收发)
                     └────────┬─────────┘
                              │
                     ┌────────▼─────────┐
                     │  ContentService  │  (用例编排：DTO→Task)
                     └────────┬─────────┘
                         │
                     ┌────────▼─────────┐
                     │   Coordinator    │  (调度中心：编排流水线)
                     └────────┬─────────┘
                              │ 依赖抽象
                     ┌────────▼─────────┐
                     │   AgentManager   │  (花名册：按角色取 Agent)
                     └────────┬─────────┘
                              │ 持有
              ┌───────────────┼───────────────┐
              ▼               ▼               ▼
      ┌─────────────┐ ┌─────────────┐  ...  ┌─────────────┐
      │ PlannerAgent│ │ResearchAgent│       │ReviewerAgent│  (具体 Agent)
      └──────┬──────┘ └──────┬──────┘       └──────┬──────┘
             │ 都继承  │                     │
             └───────────┬───┴─────────────────────┘
                         ▼
                 ┌───────────────┐
                 │ AbstractAgent │  (模板方法：统一日志/计时/兜底)
                 └───────┬───────┘
                         │ 实现
                 ┌───────▼───────┐
                 │  Agent (接口)  │  (最小契约：role + execute)
                 └───────────────┘

     ┌──────────────────────────────────────────┐
     │  横切依赖（所有 Agent 都用到）：              │
     │  • AgentContext  ── 公文包（task+memory+logs）│
     │  • SharedMemory  ── 黑板（Agent 间交换数据）  │
     │  • LlmClient     ── 可插拔大模型接口          │
     │  • AgentResult   ── 统一结果契约              │
     └──────────────────────────────────────────┘
```

**记住这张图**：本章接下来就是逐个把图里的方框写成 Java 类。

---

## 3.3 目录结构规划

严格遵循"所有代码只放 `day08multiagent` 目录"的约束，我们规划如下包结构：

```
day08multiagent/
├── agent/
│   ├── core/          # 框架核心：Agent 接口、抽象基类、上下文、结果、角色
│   │   ├── Agent.java
│   │   ├── AbstractAgent.java
│   │   ├── AgentContext.java
│   │   ├── AgentResult.java
│   │   └── AgentRole.java
│   ├── message/       # 消息模型：Task、Message
│   │   ├── Task.java
│   │   └── Message.java
│   ├── memory/        # 共享记忆：SharedMemory（黑板）
│   │   └── SharedMemory.java
│   ├── planner/       # 规划 Agent
│   ├── research/      # 研究 Agent
│   ├── writer/        # 写作 Agent
│   ├── reviewer/      # 评审 Agent
│   └── coordinator/   # 协调者 + Agent 花名册
│       ├── Coordinator.java
│       └── AgentManager.java
├── config/            # 可插拔 LLM：LlmClient 接口 + MockLlmClient
├── controller/        # HTTP 入口
├── service/           # 应用服务
├── dto/               # 数据传输对象
├── entity/            # 审计日志实体
└── docs/chapters/     # 本套教学文档
```

**设计说明**：为什么把 `core` 单独拎出来？因为 core 是"框架"，其他都是"业务"。框架代码稳定、极少改动；业务代码（各 Agent）频繁迭代。物理上分开，符合"稳定与易变分离"的架构直觉。

---

## 3.4 第一根柱子：Agent 统一接口

一切的起点是一个最小接口。我们要让所有 Agent 都长成同一个样子，Coordinator 才能一视同仁地调度它们（这就是 LSP + DIP 的地基）。

```java
public interface Agent {
    /** 我是谁（哪个角色） */
    AgentRole role();

    /** 执行我的职责：读 context → 产出 → 写回 memory → 返回 result */
    AgentResult execute(AgentContext context);
}
```

**设计要点逐条解析**：

1. **为什么只有两个方法？**（ISP）接口越小，实现越自由、越稳定。`role()` 让调度器识别身份，`execute()` 让调度器驱动执行，够用了。
2. **为什么输入是 `AgentContext` 而不是一堆散参数？** 因为协作过程中要传递的东西会增长（task、memory、logs……）。用一个"公文包"打包，将来加字段不用改接口签名（又是 OCP）。
3. **为什么返回 `AgentResult` 而不是直接返回 String？** 因为 Coordinator 需要知道"成功还是失败"来决定下一步。统一的结果契约让调度逻辑不用关心每个 Agent 的内部细节（DIP）。

> 💡 **面试高频**：为什么不用抽象类而用接口？因为 Java 单继承，用接口能让 Agent 将来还能实现别的接口（如 `Comparable`），保持灵活性。抽象类留给"共享实现"（下一节的 `AbstractAgent`）。

---

## 3.5 第二根柱子：AbstractAgent 模板方法基类

如果每个 Agent 都自己写"计时、打日志、抓异常"，那会有大量重复代码，而且总有人漏写异常处理。我们用**模板方法模式**把这些"横切关注点"统一固化。

```java
@Slf4j
public abstract class AbstractAgent implements Agent {

    // final：子类不可覆盖，锁死横切逻辑
    @Override
public final AgentResult execute(AgentContext context) {
        LocalDateTime start = LocalDateTime.now();
  long startNanos = System.nanoTime();
        String inputSummary = summarizeInput(context);
        log.info("[{}] 开始执行，输入摘要：{}", role().getDisplayName(), inputSummary);

        AgentResult result;
        try {
            result = doExecute(context);          // 子类的真正业务
            if (result == null) {
                result = AgentResult.fail(role(), "doExecute 返回了 null");
            }
        } catch (Exception e) {                   // 统一兜底：异常绝不外抛
            log.error("[{}] 执行异常，已兜底：{}", role().getDisplayName(), e.getMessage(), e);
            result = onError(context, e);
        }

        long cost = (System.nanoTime() - startNanos) / 1_000_000;
        result.setCostMillis(cost);
        result.setRole(role());
        context.addLog(AgentExecutionLog.of(context.nextStep(), role(),
                result.isSuccess(), inputSummary, truncate(result.getOutput()),
                cost, start, result.getMessage()));   // 写审计日志
        return result;
    }

    // 子类实现：真正的业务逻辑
    protected abstract AgentResult doExecute(AgentContext context) throws Exception;

    // 钩子：可覆盖的输入摘要
    protected String summarizeInput(AgentContext context) {
        return "topic=" + context.getTask().getTopic();
    }

    // 钩子：可覆盖的异常兜底（默认转失败）
    protected AgentResult onError(AgentContext context, Exception e) {
        return AgentResult.fail(role(), role().getDisplayName() + " 执行失败：" + e.getMessage());
    }
}
```

**模板方法模式的三个关键设计**：

1. **`execute()` 用 `final` 锁死**：这是模板方法模式的精髓。子类**不能**改动执行骨架，只能填空（`doExecute`）。这保证了"无论哪个 Agent，计时和日志的逻辑都一模一样"——横切逻辑改一处、全体生效（OCP）。

2. **异常绝不外抛**：`try-catch` 兜住 `doExecute` 抛出的任何异常，转成 `AgentResult.fail(...)`。这直接落地了第二章的"避坑 3：单个 Agent 挂掉不能拖垮 Coordinator"。这是生产系统健壮性的命门。

3. **钩子方法（Hook）**：`summarizeInput()` 和 `onError()` 有默认实现，子类**可选**覆盖。想要更贴切的日志摘要？覆盖 `summarizeInput`。想要降级而非直接失败？覆盖 `onError`。这是模板方法模式提供的"可定制扩展点"。

> 💡 **类比记忆**：模板方法就像"填空题的试卷"。老师（基类）印好了试卷格式（execute 骨架），题干、评分栏、姓名栏都固定了；学生（子类）只能在"答题区"（doExecute）写答案，不能改试卷格式。

---

## 3.6 第三、四根柱子：AgentContext 与 AgentResult

### 3.6.1 AgentContext —— 流动的公文包

```java
@Getter
public class AgentContext {
    private final Task task;                              // 我们在为什么目标工作
    private final SharedMemory memory;                    // 共享黑板
    private final List<AgentExecutionLog> logs = new ArrayList<>();  // 执行日志
    private int step = 0;                                 // 当前第几步

    public AgentContext(Task task, SharedMemory memory) {
        this.task = task;
        this.memory = memory;
    }

    public void addLog(AgentExecutionLog log) {           // 由基类自动调用
        this.step++;
        log.setStep(this.step);
        this.logs.add(log);
    }

    public int nextStep() { return this.step + 1; }
}
```

**设计取舍**：Context 只装"协作范围内需要共享"的东西（task、memory、logs），不装 Agent 私有的临时变量。否则会犯第二章说的"上下文无限膨胀"的错。它是 `SharedMemory` 的宿主，但比黑板多了 task 和 logs 这两个"协作级"信息。

### 3.6.2 AgentResult —— 统一的结果契约

```java
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class AgentResult {
    private AgentRole role;      // 谁产出的
    private boolean success;     // 成功还是失败
    private String output;       // 核心产出摘要
    private String message;      //�加说明/失败原因
    private long costMillis;     // 耗时

    public static AgentResult ok(AgentRole role, String output) { ... }
    public static AgentResult fail(AgentRole role, String message) { ... }
}
```

**为什么要静态工厂 `ok()` / `fail()`？** 让调用方一眼看清意图：`return AgentResult.ok(role(), "大纲已生成")` 比 `new AgentResult(role(), true, "大纲已生成", "ok", 0)` 可读得多。这是"表达力优于构造器"的实践。

---

## 3.7 第五根柱子：SharedMemory 黑板

黑板是 Multi-Agent 的灵魂——**Agent 之间不直接通话，全靠黑板交换数据**。

```java
@Slf4j
public class SharedMemory {

    // 黑板键约定：所有 Agent 必须用这里的常量，禁止裸写字符串
    public static final class Keys {
        private Keys() {}
        public static final String OUTLINE = "outline";      // 大纲 List<String>
        public static final String MATERIALS = "materials";  // 素材 Map<String,String>
        public static final String DRAFT = "draft";          // 草稿 String
        public static final String REVIEW = "review";        // 评审意见 String
        public static final String SCORE = "score";          // 评审分数 Double
    }

    private final Map<String, Object> board = new ConcurrentHashMap<>();

    public void put(String key, Object value) { board.put(key, value); }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Object v = board.get(key);
        return (v == null || !type.isInstance(v)) ? null : (T) v;
    }
}
```

**两个关键设计**：

1. **`Keys` 常量类固化"暗号"**：这是第二章"避坑 2：黑板键要有约定"的落地。如果 PlannerAgent 写 `"outline"`、WriterAgent 读 `"outLine"`（大小写不一致），流水线就断了，还极难排查。集中定义常量，编译期就能杜绝这类错误。

2. **`ConcurrentHashMap` 线程安全**：V1 是顺序执行，本不需要并发容器。但我们为将来的"并行聚合"策略（多个 Agent 并发写黑板）预留能力。这是"面向未来的适度设计"。

---

## 3.8 第六根柱子：可插拔的 LlmClient

这是 DIP 最典型的落地。业务 Agent 需要"调大模型"，但绝�不能直接依赖某个厂商的 SDK。

```java
// 抽象：业务 Agent 只认这个接口
public interface LlmClient {
    String chat(String systemPrompt, String userPrompt);
    String name();
}

// 默认实现：基于规则的 Mock，开箱即用不依赖 API Key
@Component
public class MockLlmClient implements LlmClient {
    @Override
    public String chat(String systemPrompt, String userPrompt) {
        if (systemPrompt.contains("规划")) return mockPlan(userPrompt);
        if (systemPrompt.contains("研究")) return mockResearch(userPrompt);
        // ...按角色分流返回模拟内容
    }
    @Override public String name() { return "MockLlmClient"; }
}
```

**为什么要 Mock 实现？** 让学生 clone 下来 **立刻能跑通** 四个 Agent 的完整流水线，看到真实的协作过程，而不是卡在"没有 OpenAI Key"这一步。将来接真实模型，只需：

```java
@Component
@Primary   // 用 @Primary 或配置开关顶替 Mock
public class OpenAiLlmClient implements LlmClient {
    @Override public String chat(String sp, String up) { /* 真调 OpenAI */ }
    @Override public String name() { return "OpenAI"; }
}
```

**Agent 业务代码一行都不用改**——这就是依赖倒置的红利。

---

## 3.9 企业案例：某内容平台的"抗变化"架构复盘

某 MCN 机构的内容中台，最初用一个 `ArticleService` 硬编码整个生成流程。半年内经历了三次大改，每次都是"全量回归测试 + 通宵发布"。后来重构为本章这套 SOLID 骨架后：

| 变更需求 | 重构前改动范围 | 重构后改动范围 |
| --- | --- | --- |
| 换大模型供应商 | 改 ArticleService + 3 处调用点 | 新增 1 个 LlmClient 实现 |
| 新增"配图 Agent" | 改 Service + 改流程 if-else | 新增 1 个 Agent 类 |
| 评审规则升级 | 改 Service 里的评审段落 | 只改 Coordinator 调度循环 |

**结论**：SOLID 不是"炫技"，是实打实地把"改一次代码要动的地方"从 N 处压缩到 1 处，直接决定迭代速度和线上稳定性。

---

## 3.10 常见问题 FAQ

**Q1：接口和抽象类到底怎么选？**
A：接口定义"能力契约"（能做什么），抽象类提供"共享实现"（怎么做的公共部分）。本项目 `Agent` 是接口（契约），`AbstractAgent` 是抽象类（共享横切逻辑）。两者配合是经典组合。

**Q2：`execute()` 为什么要 `final`？不加会怎样？**
A：不加 `final`，某个子类可能"手滑"覆盖了 `execute()`，绕过了计时和异常兜底，导致这个 Agent 挂了就把整个流程带崩。`final` 是把横切逻辑"焊死"，防止破坏契约。

**Q3：黑板用 Map 会不会太"弱类型"，容易出错？**
A：会有这个风险，所以我们用 `Keys` 常量约束键、用 `get(key, Class<T>)` 做类型转换兜底。更严格的做法是给黑板做强类型封装，但对教学项目而言，当前设计在"灵活"与"安全"间取得了平衡。

**Q4：Coordinator 直接 new 各个 Agent 不行吗？为什么要 AgentManager？**
A：直接 new 就把 Coordinator 和具体 Agent 类"焊死"了（违反 DIP）。AgentManager 用 Spring 自动装配收集所有 Agent，Coordinator 只按角色取用，新增 Agent 时 Coordinator 零改动（OCP）。

---

## 3.11 面试高频题

1. **请用一个你写过的项目，说明 SOLID 中的开闭原则。**
   （参考答案：本项目新增 Agent 只需加类、不改老代码，即 AgentManager 自动装配机制。）

2. **模板方法模式解决了什么问题？和策略模式有什么区别？**
   （参考答案：模板方法固定算法骨架、子类填空，是"继承"复用；策略模式封装可互换的算法，是"组合"复用。本项目 AbstractAgent 用模板方法，LlmClient 用策略思想。）

3. **依赖倒置和依赖注入是一回事吗？**
   （参考答案：不是。依赖倒置是"依赖抽象"的设计原则；依赖注入是"由外部传入依赖"的实现手段。本项目用 Spring 构造器注入 LlmClient，是用 DI 手段实现了 DIP 原则。）

4. **为什么 execute() 里要捕获所有异常而不是抛出去？**
   （参考答案：保证单个 Agent 的失败不会中断整个协作流程，Coordinator 能拿到 fail 结果做降级/记录，这是分布式/多组件系统的隔离性要求。）

---

## 3.12 本章练习（含参考答案）

**练习 1**：给 `AgentRole` 枚举新增一个 `TRANSLATOR`（翻译者）角色，说明这符合哪条 SOLID 原则。

<details><summary>参考答案</summary>

```java
TRANSLATOR("翻译者", "把中文文章翻译成目标语言");
```
符合 **OCP（开闭原则）**：通过新增枚举值扩展系统能力，未修改任何已有角色的定义。
</details>

**练习 2**：`AbstractAgent.onError()` 默认返回失败结果。请为某个 Agent 覆盖它，实现"失败时返回一个占位产出"的降级策略。

<details><summary>参考答案</summary>

```java
@Override
protected AgentResult onError(AgentContext ctx, Exception e) {
    // 降级：写一个占位大纲，让流程能继续
    ctx.getMemory().put(SharedMemory.Keys.OUTLINE, List.of("（降级占位大纲）"));
    return AgentResult.ok(role(), "已降级为占位产出");
}
```
体现模板方法的"钩子方法可定制"特性。
</details>

**练习 3**：如果要把 `LlmClient` 从"同步返回 String"改成"流式返回"，会影响哪些类？如何最小化改动？

<details><summary>参考答案</summary>

最小化改动方案：**不改** `LlmClient` 老接口，新增一个 `StreamLlmClient` 子接口（ISP），只让需要流式的 Agent 依赖新接口。老 Agent 完全不受影响。这是接口隔离 + 开闭原则的组合运用。
</details>

---

## 3.13 本章任务

> ✅ **动手清单**（对应代码已在 `day08multiagent` 目录，可直接对照阅读、编译运行）

1. 阅读 `agent/core/Agent.java`，理解最小接口设计。
2. 阅读 `agent/core/AbstractAgent.java`，找出 `final execute()`、`try-catch 兜底`、`钩子方法` 三处关键设计。
3. 阅读 `agent/memory/SharedMemory.java`，理解 `Keys` 常量类的作用。
4. 阅读 `config/LlmClient.java` 与 `config/MockLlmClient.java`，理解可插拔设计。
5. 在本地用 JDK 17 执行 `mvn compile`，确认框架骨架编译通过。
6. **挑战题**：尝试新建一个 `TranslatorAgent`，观察是否真的"不用改任何老代码"就能被 `AgentManager` 收录。

**下一章预告**：第四章我们将逐行剖析四个具体 Agent（Planner/Research/Writer/Reviewer）的完整实现，看它们如何在黑板上"接力"，最终产出一篇完整文章。