# 第四章 消息路由（Message Routing）

> 上一章我们把「会话」立了起来——机器人终于能记住"你是谁、我们聊到哪了"。
> 但会话只解决了"上下文"，没有解决"分工"。
> 一条消息进来，到底该谁处理？`/help` 是命令，一张图片要走图片理解，一句"帮我订个会议室"要走 LLM 对话……
> **本章要解决的核心问题只有一句话：一条消息进来后，Agent 怎么决定这该谁处理。**

---

## 一、为什么学：没有路由的 Agent 是一坨 if-else

### 1.1 一个真实的技术债现场

我见过一个线上企微机器人的消息入口方法，叫 `onMessage()`，**一共 1400 行**。它长这样（简化版）：

```java
public void onMessage(Message msg) {
    if (msg.getType().equals("text")) {
        String content = msg.getContent();
        if (content.startsWith("/help")) {
            // 200 行帮助逻辑
        } else if (content.startsWith("/reset")) {
            // 80 行重置逻辑
        } else if (content.startsWith("/bind")) {
            // ...
        } else if (content.contains("会议室") || content.contains("订会议")) {
            // 300 行订会议室逻辑，还夹着 3 层嵌套 if
        } else if (content.contains("请假")) {
            // ...
        } else {
            // 兜底调 LLM
        }
    } else if (msg.getType().equals("image")) {
        // 图片理解
    } else if (msg.getType().equals("file")) {
        // 文件解析
    } else if (...) { ... }
}
```

这段代码的问题，**每一条都是生产事故的温床**：

| 问题 | 后果 |
| --- | --- |
| 所有意图挤在一个方法里 | 改订会议室的逻辑，手一抖碰坏了请假的分支 |
| `if-else` 顺序即优先级，但没人写清楚 | 新人在中间插一个 `else if`，把后面的分支全饿死了 |
| 新增一个意图 = 改这个 1400 行方法 | 违反开闭原则（OCP），每次上线都要回归全量 |
| 无法单元测试单个意图 | 想测"订会议室"，得把整个 `onMessage` 的依赖全 mock 一遍 |
| 无法按意图观测 | 老板问"命令类消息占比多少"，答不上来——所有消息在指标里都是一坨 |

### 1.2 路由的本质：把"分发决策"和"处理逻辑"分开

**消息路由（Message Routing）** 要做的，就是把上面那坨 `if-else` 拆成两层：

1. **决策层（路由器）**：只负责"这条消息该谁处理"，不关心具体怎么处理。
2. **处理层（处理器）**：每个处理器只管一种意图，互不干扰，可独立开发、独立测试、独立上线。

这就像公司前台：前台不负责修电脑、不负责报销、不负责面试，它只负责**把来访者领到正确的部门**。修电脑的是 IT 部，报销的是财务部，各司其职。前台换人、IT 部扩编，互不影响。

### 1.3 学完本章你能回答

- 意图识别（Intent Recognition）在 Agent 里处于什么位置？
- 责任链模式和策略模式，在路由里各自扮演什么角色？
- 为什么优先级排序如此重要？顺序错了会怎样？
- 怎么做到"新增一个意图不改路由器一行代码"（OCP）？
- 兜底处理器（fallback）为什么是生产系统的生命线？

---

## 二、是什么：路由的三个核心概念

### 2.1 意图（Intent）：消息的"目的地标签"

意图就是"这条消息想干什么"。我们先用一个粗粒度的枚举把世界切成四类：

| 意图 | 触发条件 | 归谁处理 | 举例 |
| --- | --- | --- | --- |
| `COMMAND` | 文本以 `/` 开头 | 命令系统（第五章） | `/help`、`/reset`、`/bind` |
| `MULTIMODAL` | 图片 / 文件消息 | 图片理解 / 文件解析 | 发一张报销单截图 |
| `CHAT` | 普通自然语言文本 | LLM 对话（后续章节） | "帮我订个会议室" |
| `UNKNOWN` | 都不匹配 / 兜底 | 回显或提示 | 一条空消息、未知类型 |

> 注意：这里的意图是**结构化粗分类**，不是 NLU 语义意图。真正细粒度的语义意图（"订会议室" vs "查会议室"）留给 LLM 或命令系统去做。路由层只做"体力活"的初筛，把消息甩给对的部门。

对应代码是 [`IntentType`](../../wechatrouter/domain/model/IntentType.java:1) 枚举，带 `code` 和 `label` 两个字段，便于日志与监控埋点。

### 2.2 责任链模式（Chain of Responsibility）：处理器排队认领

责任链的核心思想：**把处理器排成一队，消息沿着队伍走，谁认领谁处理，认领了就停。**

```
消息进来
   |
   v
[CommandHandler]  order=10   -- supports? 是 -> 处理，收工
   | 否
   v
[MultimodalHandler] order=20 -- supports? 是 -> 处理，收工
   | 否
   v
[ChatHandler]     order=30   -- supports? 是 -> 处理，收工
   | 否
   v
[EchoHandler]     order=MAX  -- supports? 恒为真 -> 兜底回显
```

每个处理器实现同一个接口 [`MessageHandler`](../../wechatrouter/domain/port/MessageHandler.java:1)，暴露三个能力：

- `supports(message)`：我认领这条消息吗？
- `handle(message)`：认领了，我来处理，返回结果。
- `order()`：我在队伍里排第几？（数字越小越靠前）

### 2.3 策略模式（Strategy）：每个处理器是一种可插拔策略

责任链解决"排队"，策略模式解决"可插拔"。

每个 `MessageHandler` 实现就是一种独立策略。它们通过 Spring 的集合注入被自动收集：

```java
public MessageRouter(List<MessageHandler> handlers) { ... }
```

**这行代码是本章的灵魂**：Spring 会把容器里所有 `MessageHandler` 类型的 Bean 自动装进这个 List。这意味着——

> 新增一个意图，你只需要写一个新的 `@Component` 实现 `MessageHandler`，**路由器一行都不用改**。这就是开闭原则（OCP）：对扩展开放，对修改关闭。

### 2.4 两个模式的分工

| 模式 | 解决什么 | 在本章的体现 |
| --- | --- | --- |
| 责任链 | "按顺序问，谁认领谁处理" | `order()` 排序 + 遍历 `supports` |
| 策略 | "每种处理是可替换的独立单元" | 每个 `MessageHandler` 实现 + 集合注入 |

一句话记忆：**责任链管"顺序"，策略管"可插拔"，两者合体就是一个既有序又开放的路由系统。**

---

## 三、怎么用：从端口到编排，五段代码吃透路由

### 3.1 处理器端口 MessageHandler（责任链节点）

这是整个模块的"契约"。所有处理器都实现它，路由器只依赖它。

```java
public interface MessageHandler {
    int DEFAULT_ORDER = 100;
    IntentType intentType();               // 我负责哪种意图（观测用）
    boolean supports(InboundMessage msg); // 我认领这条消息吗
    HandleResult handle(InboundMessage msg); // 认领了，我来处理
    default int order() { return DEFAULT_ORDER; } // 我排第几
}
```

**设计要点逐条拆解：**

- `intentType()` 不参与路由决策，纯粹用于日志和监控埋点。生产里你会想按意图统计消息分布，这个字段就是维度标签。
- `supports()` 和 `handle()` 拆成两个方法，而不是让 `handle` 返回"我没处理"——**这是责任链的关键**。先问"你要不要"，再让它"动手做"，职责清晰，也避免副作用（万一 handle 里已经改了状态才发现不该处理）。
- `order()` 用 `default` 方法给了默认值 100。普通处理器可以不重写；但**命令、多模态、闲聊、兜底这四个必须显式给值**，因为它们的相对顺序是有讲究的（见 3.5）。
- 端口只依赖 `InboundMessage`（第二章）和本模块的值对象，**不依赖任何 Spring 注解**——这是六边形架构的纪律：领域端口保持纯净。

### 3.2 处理结果 HandleResult（认领 + 回复 + 是否终止）

```java
public record HandleResult(boolean handled, OutboundMessage reply, boolean terminal) {
    public static HandleResult replied(OutboundMessage reply) {
        return new HandleResult(true, reply, true);   // 已处理 + 有回复 + 终止链路
    }
    public static HandleResult handledSilently() {
        return new HandleResult(true, null, true);    // 已处理 + 无回复 + 终止（静默记录场景）
    }
    public static HandleResult pass() {
        return new HandleResult(false, null, false);  // 不认领，交给下一个
    }
    public boolean hasReply() { return reply != null; }
}
```

**为什么要三个字段而不是简单返回一个 OutboundMessage？**

因为现实比"有回复/没回复"复杂：

- **静默处理**：有些消息处理了但不需要回复（比如打点、审计日志）。`handledSilently()` 覆盖这种情况——`handled=true` 但 `reply=null`。
- **认领后放行**：极少数场景一个处理器想"预处理一下再传给下一个"，用 `pass()`。本章的四个处理器都用 `replied()`（认领即终止），但端口保留了这个能力。
- **不可变**：用 `record` 保证结果值对象不可变，路由器拿到后不会被谁偷偷改掉。

### 3.3 路由器 MessageRouter（应用层编排核心）

这是本章代码的心脏。请逐行读：

```java
@Service
public class MessageRouter {
    private final List<MessageHandler> handlers;

    public MessageRouter(List<MessageHandler> handlers) {
        this.handlers = handlers.stream()
                .sorted(Comparator.comparingInt(MessageHandler::order))  // (1) 构造期排序
                .toList();                                               // (2) 固化为只读
        log.info("责任链共 {} 个处理器：{}", ...);
    }

    public RoutingResult route(InboundMessage message) {
        if (!message.shouldHandle()) {                        // (3) 前置守卫：群聊未@直接短路
            return RoutingResult.unhandled(IntentType.UNKNOWN);
        }
        for (MessageHandler handler : handlers) {             // (4) 按序遍历
            if (!handler.supports(message)) continue;         // (5) 不认领，问下一个
            HandleResult result = handler.handle(message);    // (6) 认领了，动手
            if (result.handled()) {                           // (7) 处理了就返回
                return RoutingResult.handled(handler.intentType(),
                        handler.getClass().getSimpleName(), result.reply());
            }
            if (result.terminal()) break;                     // (8) 主动终止
        }
        return RoutingResult.unhandled(IntentType.UNKNOWN);   // (9) 无人认领（生产不该发生）
    }
}
```

**九个关键点：**

1. **构造期排序**：排序只做一次，发生在 Bean 初始化时，不是每条消息都排。运行期热路径零排序开销。
2. **固化为只读**：`.toList()` 返回不可变列表，运行期没人能往责任链里塞东西，线程安全。
3. **前置守卫**：`shouldHandle()`（第二章的方法）在最前面拦掉群聊没被 @ 的消息，避免机器人在群里刷屏。这是"最省算力"的一刀——连责任链都不用进。
4. **按序遍历**：`for` 循环沿着排好序的链走，顺序即优先级。
5. **不认领就跳过**：`supports` 返回 false，`continue` 问下一个，责任链的精髓。
6. **认领才动手**：只有 `supports` 通过才调 `handle`，避免无谓副作用。
7. **处理了就返回**：拿到 `handled=true` 立即封装 `RoutingResult` 返回，后面的处理器不再打扰。
8. **主动终止**：处理器返回 `terminal=true` 但 `handled=false`（罕见），也跳出。
9. **兜底告警**：走到这里说明连 EchoHandler 都没认领——正常绝不可能（Echo 恒真），所以这里 `log.warn` 是给"忘了注册兜底处理器"的自己埋的报警。

### 3.4 四个处理器：优先级即业务规则

| 处理器 | order | supports 条件 | handle 行为 |
| --- | --- | --- | --- |
| [`CommandHandler`](../../wechatrouter/infrastructure/CommandHandler.java:1) | 10 | 文本且以 `/` 开头 | 占位回复，第五章接命令系统 |
| [`MultimodalHandler`](../../wechatrouter/infrastructure/MultimodalHandler.java:1) | 20 | `isMultimodal()`（图片/文件） | 占位回复，后续接 Vision/RAG |
| [`ChatHandler`](../../wechatrouter/infrastructure/ChatHandler.java:1) | 30 | 非空普通文本 | 占位回复，后续接 LLM |
| [`EchoHandler`](../../wechatrouter/infrastructure/EchoHandler.java:1) | `Integer.MAX_VALUE` | **恒为 true** | 回显消息，兜底 |

**为什么是这个顺序？** 因为 `supports` 条件有重叠：

- `/help` 既是"以 `/` 开头"（命令），也是"非空普通文本"（闲聊）。如果 ChatHandler 排在 CommandHandler 前面，`/help` 就会被闲聊处理器抢走，命令系统永远收不到消息——**这就是顺序错了导致的"分支饥饿"**。
- 所以：**越具体的条件，优先级越高（order 越小）**。命令（前缀明确）> 多模态（类型明确）> 闲聊（兜住所有文本）> 回显（兜住一切）。

### 3.5 兜底处理器 EchoHandler：系统的生命线

```java
@Component
public class EchoHandler implements MessageHandler {
  private static final int ORDER = Integer.MAX_VALUE;   // 永远排最后
    @Override public boolean supports(InboundMessage m) { return true; }  // 认领一切
    @Override public HandleResult handle(InboundMessage m) {
        return HandleResult.replied(OutboundMessage.textReply(m, "[echo] " + m.getContent()));
    }
    @Override public int order() { return ORDER; }
}
```

它做了两件了不起的小事：

1. **`supports` 恒为 true + order 最大**：保证任何消息都不会掉进黑洞。用户发了消息机器人不吭声，是 IM 机器人最糟糕的体验——EchoHandler 用最低成本堵住了这个洞。
2. **零依赖可运行**：不调 LLM、不连数据库，纯回显。这让整个 wechatrouter 模块在**没有任何外部依赖**的情况下就能跑起来、能演示、能写单元测试。先能跑，再逐步把占位处理器换成真家伙——这是渐进式交付的工程智慧。

---

## 四、真实项目：一条消息在责任链里的完整旅程

让我们跟着四条不同的消息，走一遍完整的路由链路，看每一步发生了什么。

### 场景设定

租户 `acme` 的企微群 `group-42` 里，机器人已被添加。责任链启动后按 order 排好队：

```
CommandHandler(10) -> MultimodalHandler(20) -> ChatHandler(30) -> EchoHandler(MAX)
```

### 消息一：`/help`（单聊）

```
1. route() 收到消息，shouldHandle()=true（单聊恒处理），进链。
2. CommandHandler.supports()：TEXT 且以 "/" 开头 -> true！命中。
3. CommandHandler.handle()：返回 markdown 占位回复 "已识别到命令：/help ..."。
4. result.handled()=true -> 封装 RoutingResult(intent=COMMAND,handler=CommandHandler)，返回。
   -> MultimodalHandler / ChatHandler / EchoHandler 根本没被问到。
```

**关键**：命令排第一，`/help` 稳稳被命令系统接走，不会被闲聊抢。

### 消息二：一张报销单截图（单聊）

```
1. shouldHandle()=true，进链。
2. CommandHandler.supports()：messageType 是 IMAGE，不是 TEXT -> false，跳过。
3. MultimodalHandler.supports()：isMultimodal()=true（IMAGE） -> true！命中。
4. handle()：返回 "已收到IMAGE消息（含媒体资源），多模态解析能力将在后续章节接入"。
5. 返回 RoutingResult(intent=MULTIMODAL)。
```

**关键**：图片天然不满足命令的 TEXT 条件，被顺位交给多模态处理器。类型即路由。

### 消息三："帮我订个会议室"（群聊，@了机器人）

```
1. shouldHandle()：群聊 + mentioned=true -> true，进链。
2. CommandHandler.supports()：TEXT 但不以 "/" 开头 -> false，跳过。
3. MultimodalHandler.supports()：不是图片/文件 -> false，跳过。
4. ChatHandler.supports()：非空普通文本 -> true！命中。
5. handle()：返回 "收到你的消息：帮我订个会议室（智能对话能力将在后续章节接入 LLM）"。
6. 返回 RoutingResult(intent=CHAT)。
```

**关键**：自然语言最终落到闲聊处理器，后续章节这里会换成 LLM + Memory + Planning，实现真正的"订会议室"能力。

### 消息四：群聊里一句没 @ 机器人的闲聊

```
1. shouldHandle()：群聊 + mentioned=false -> false！
2. route() 前置守卫直接短路，返回 unhandled，连责任链都没进。
```

**关键**：这一刀省下了全部算力。群里 99% 的消息都不是发给机器人的，前置守卫是性能和体验的双重保护——机器人不会在群里抢话。

### 旅程小结

| 消息 | shouldHandle | 命中处理器 | 意图 | 走了几步 |
| --- | --- | --- | --- | --- |
| `/help` | true | CommandHandler | COMMAND | 1 步命中 |
| 图片 | true | MultimodalHandler | MULTIMODAL | 2 步命中 |
| "订会议室" | true | ChatHandler | CHAT |3 步命中 |
| 群聊未@ | **false** | 无（短路） | UNKNOWN | 0 步（守卫拦截） |

一条消息进来到底该谁处理——现在你能一步步说清楚了。

---

## 五、避坑指南：12 条路由血泪

1. **优先级顺序错，分支饿死。** 把 ChatHandler 排在 CommandHandler 前，`/help` 会被闲聊抢走，命令系统永远收不到消息。**规则：越具体的条件 order 越小。**

2. **忘了兜底处理器，消息掉黑洞。** 没有 EchoHandler，未匹配的消息会走到 `route()` 末尾返回 unhandled，用户发消息机器人不吭声。**兜底处理器 `supports` 必须恒真、order 必须最大。**

3. **`supports` 里写副作用。** 有人图省事在 `supports` 里改了状态或发了请求，结果一条消息被多个处理器"问"了一遍，副作用执行多次。**`supports` 必须是纯函数，只判断不干活。**

4. **每条消息都重新排序。** 把 `sorted()` 写进 `route()` 而非构造函数，热路径上每条消息排一次序，QPS 一高就成性能瓶颈。**排序只在构造期做一次。**

5. **责任链用可变 List。** 用 `ArrayList` 存 handlers 且暴露出去，运行期被人 `add`，线程不安全还破坏顺序。**构造期 `.toList()` 固化为不可变。**

6. **前置守卫缺失，群聊刷屏。** 忘了 `shouldHandle()` 判断，机器人在群里对每句话都回复，被踢群是分分钟的事。**群聊未 @ 必须在进链前短路。**

7. **`order()` 用了相同的值。** 两个处理器 order 都是 10，排序结果不稳定（取决于 Bean 注册顺序），线上行为飘忽。**关键处理器的 order 必须两两不同、有明确间隔（10/20/30 留扩展空间）。**

8. **命令前缀判断没 strip。** 用户输入 " /help"（前面有空格），`startsWith("/")` 判 false，命令识别失效。**判断前先 `content.strip()`。**

9. **handle 抛异常没兜住。** 某个处理器内部 NPE，整个 `route()` 崩了，用户啥也收不到。**生产里应在 route 外层（API 层）包一层 try-catch，异常时返回友好提示。** 本章占位实现简单，异常兜底放在第九章 API 层统一做。

10. **RoutingResult 不带 handler 名，无法排障。** 线上出问题，日志只说"处理了"，不知道是谁处理的。**RoutingResult 必须带 intentType 和 handlerName，便于按维度观测与定位。**

11. **意图枚举当语义意图用。** 有人想在 IntentType 里加 `BOOK_MEETING`、`ASK_LEAVE` 几十个语义意图，枚举爆炸。**路由层的 IntentType 是粗分类，细粒度语义意图交给 LLM/命令系统，别在这里堆。**

12. **占位处理器直接上线当成品。** 占位回复"能力将在后续章节接入"如果忘了替换就发布，用户看到一脸懵。**占位实现必须有明确 TODO 标记，上线前 review 清点。**

---

## 六、本章小结

| 概念 | 一句话 | 对应代码 |
| --- | --- | --- |
| 意图 IntentType | 消息的粗粒度目的地标签 | [`IntentType`](../../wechatrouter/domain/model/IntentType.java:1) |
| 处理器端口 | 责任链节点契约（supports/handle/order） | [`MessageHandler`](../../wechatrouter/domain/port/MessageHandler.java:1) |
| 处理结果 | 认领 + 回复 + 是否终止 | [`HandleResult`](../../wechatrouter/domain/model/HandleResult.java:1) |
| 路由结果 | 意图 + 处理器名 + 回复（可观测） | [`RoutingResult`](../../wechatrouter/domain/model/RoutingResult.java:1) |
| 路由器 | 排序固化 + 前置守卫 + 遍历分发 | [`MessageRouter`](../../wechatrouter/application/MessageRouter.java:1) |
| 责任链模式 | 排队认领，谁认领谁处理 | order 排序 + supports 遍历 |
| 策略模式 | 每个处理器是可插拔独立策略 | List 集合注入 |
| 开闭原则 OCP | 新增意图不改路由器 | 新增 @Component 实现即可 |
| 兜底处理器 | supports 恒真、order 最大，堵住黑洞 | [`EchoHandler`](../../wechatrouter/infrastructure/EchoHandler.java:1) |

**核心记忆点**：责任链管顺序，策略管插拔；越具体的条件优先级越高；兜底处理器是生命线；路由器只决策不处理。

---

## 七、面试题精选

**Q1：责任链模式和简单的 if-else 相比，好处到底在哪？说说你在真实项目里怎么用。**

> 参考答题思路：
> - if-else 把所有分支耦合在一个方法里，违反单一职责和开闭原则，改一个分支可能碰坏其他分支，无法单独测试单个分支。
> - 责任链把每个分支拆成独立处理器（策略），路由器只按优先级遍历分发。新增分支 = 新增处理器，路由器零改动（OCP）。
> - 结合 Spring：用 `List<Handler>` 集合注入自动收集所有处理器，用 `order()` 控制顺序。
> - 真实收益：每个处理器可独立单测、独立观测、独立灰度上线；意图分布可按维度埋点。

**Q2：为什么把排序放在构造函数里而不是每次路由时排？放在 route() 里会有什么问题？**

> 参考答题思路：
> - 排序结果固定、幂等，只需做一次。放构造期，Bean 初始化时排好并 `.toList()` 固化为不可变列表。
> - 放 route() 里意味着每条消息都排一次序，热路径浪费；QPS 一高，排序开销累积成瓶颈。
> - 构造期固化保证运行期责任链不可变，天然线程安全，无需加锁。

**Q3：如果两个处理器的 supports() 条件有重叠（如 `/help` 既是命令又是文本），怎么保证不出错？**

> 参考答题思路：
> - 靠 order 优先级 + "命中即终止"。越具体条件 order 越小（命令 10 < 闲聊 30），命令先被问到，认领后立即返回，闲聊没机会。
> - 这就是"越具体优先级越高"原则。顺序反了会"分支饥饿"——具体分支被通用分支抢走。
> - 设计时要梳理所有处理器 supports 条件的交集，据此确定 order。

**Q4：兜底处理器为什么重要？去掉会怎样？生产里兜底应该做什么？**

> 参考答题思路：
> - 兜底保证任何消息都不掉黑洞。去掉后未匹配消息返回 unhandled，机器人不回应——IM 最糟体验。
> - 兜底 supports 恒真、order 最大，是责任链"必须有默认分支"的落地。
> - 生产里兜底可以是友好提示、转人工、或记录到"未识别意图"看板用于优化。它还让系统零外部依赖时可运行、可测试。

---

## 八、扩展阅读

- 《设计模式：可复用面向对象软件的基础》GoF —— 责任链模式与策略模式原始定义。
- 《企业应用架构模式》Martin Fowler —— "把决策与执行分离"的架构讨论。
- Spring Framework 文档：集合类型依赖注入（`List<T>` / `Map<String,T>`）与 `@Order` / `Ordered` 接口。
- Spring Boot Actuator + Micrometer：按 tag（意图维度）记录计数器，观测各意图消息分布。
- SOLID 中的 OCP（开闭原则）与插件化架构。

---

## 九、下一章预告

现在消息能被正确分发到对应处理器了。但命令处理器目前只是占位——识别到 `/help` 却只会回"待接入"。

**第五章《命令系统》** 我们将真正实现 wechatcommand 模块：
- 命令解析器：把 `/book room=301 time=14:00` 解析成结构化命令与参数。
- 命令注册表：`@Command` 注解 + 自动注册，新增命令零配置。
- 命令执行器：参数校验、权限检查、执行、结果格式化。
- 内置命令：`/help`（自动生成帮助）、`/reset`（重置会话）、`/whoami`（用户画像）。
- 把 CommandHandler 从占位升级为真正的命令分发入口。

命令系统是 Agent "确定性能力"的骨架——当用户想要精确、可预期的操作时，命令比自然语言更可靠。下一章见。

---

---