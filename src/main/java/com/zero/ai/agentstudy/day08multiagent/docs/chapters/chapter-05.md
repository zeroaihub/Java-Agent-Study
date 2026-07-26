# 第五章 共享 Memory：黑板模式的完整设计

> 本章目标：深入剖析 `SharedMemory`（黑板）的完整设计与 API，理解它如何在"灵活"与"类型安全"之间取得平衡，以及为并行化预留的能力。这是 Multi-Agent 系统的"心脏"。

---

## 5.0 本章导读

第四章我们看到四个 Agent 在黑板上"接力"。本章把镜头对准**黑板本身**——那个被所有 Agent 共享、承载全部协作数据的对象 `SharedMemory`。

你可能会问："不就是个 Map 吗，有什么好讲的？"

事实上，从"一个裸 Map"到"一块工程级黑板"，中间隔着大量设计决策：

- 键怎么约定，才不会让 Agent "对不上暗号"？
- 类型怎么保证，才不会 `ClassCastException`？
- 读不到数据时返回什么，才不会到处 NPE？
- 线程安全怎么考虑，为并行留什么后路？
- 怎么"拍快照"，方便调试整条链路？

本章逐一拆解这些决策。理解了黑板，你就理解了 Multi-Agent 协作的本质：**用共享数据空间替代直接的对象调用**。

---

## 5.1 黑板模式（Blackboard Pattern）的本质

### 5.1.1 什么是黑板模式

黑板模式源于 AI 领域早期的专家系统。想象一群专家围着一块黑板解决问题：

- 每个专家（Agent）盯着黑板，看到"自己能处理的信息"就上前处理，把结果写回黑板；
- 专家之间**不直接对话**，全靠黑板交换信息；
- 黑板是唯一的"共享真相源"。

映射到我们的代码：

| 黑板模式概念 | 本项目对应 |
| --- | --- |
| 黑板（Blackboard） | `SharedMemory` |
| 知识源（Knowledge Source） | 四个 Agent |
| 控制器（Control） | `Coordinator` |
| 黑板上的数据项 | OUTLINE / MATERIALS / DRAFT / SCORE / REVIEW |

### 5.1.2 黑板模式的两大红利

`SharedMemory` 的类注释里写得很清楚：

```java
/**
 * ...各 Agent 之间<b>不直接互相调用</b>，而是通过 SharedMemory 交换数据：
 * 上游 Agent 把产出 put 进来，下游 Agent 再 get 出去用。这就是「黑板模式」，
 * 它带来两大好处：
 *   1. 解耦：WriterAgent 只关心「outline 和 materials 在不在」，
 *      不需要知道它们是哪个 Agent 产的；
 *   2. 可追溯：黑板本身就是一次协作的完整快照，随时可 dump 出来调试。
 */
```

**解耦**：这是最重要的红利。WriterAgent 从不 import ResearchAgent，它只认识"黑板上的 MATERIALS 这个 key"。将来把 ResearchAgent 换成从数据库读素材的 `DbResearchAgent`，WriterAgent **一行都不用改**——只要新 Agent 也往 MATERIALS 写数据。

**可追溯**：黑板是协作的"完整记录仪"。任何时刻 dump 一下，就能看到"目前进行到哪、产出了什么"，调试极其方便。

---

## 5.2 SharedMemory 完整源码剖析

### 5.2.1 类声明与存储结构

```java
@Slf4j
public class SharedMemory {

    /** 真正的黑板存储：线程安全的键值对 */
    private final Map<String, Object> board = new ConcurrentHashMap<>();
```

**逐点解读**：

- **`Map<String, Object>`**：键是 String，值是 Object（任意类型）。为什么值用 `Object` 而不是泛型？因为黑板上要存**异构数据**——OUTLINE 是 `List<String>`、DRAFT 是 `String`、SCORE 是 `Double`。用 `Object` 才能"一块黑板装万物"。代价是取用时要做类型转换（下面会看到如何安全转换）。
- **`ConcurrentHashMap`**：注意注释——"为将来「并行聚合」策略（多个 Agent 并发写）预留"。V1 顺序执行本不需要并发容器，但用它**几乎零成本**，却为未来的并行优化铺好了路。这是"适度的前瞻性设计"。
- **`private final`**：`board` 引用不可变（final），且外部拿不到（private）。所有读写必须走 SharedMemory 提供的方法，杜绝外部直接操作底层 Map。这是**封装**。

### 5.2.2 Keys 常量类：黑板的"通信协议"

```java
/**
 * 黑板键约定。所有 Agent 读写共享数据时，<b>必须</b>使用这里的常量，禁止裸写字符串。
 */
public static final class Keys {
    private Keys() {
    }

    /** 写作大纲（由 PlannerAgent 产出，List<String>） */
    public static final String OUTLINE = "outline";

    /** 收集到的素材（由 ResearchAgent 产出，Map<String,String>：小节 -> 素材） */
    public static final String MATERIALS = "materials";

    /** 正文草稿（由 WriterAgent 产出，String，Markdown） */
    public static final String DRAFT = "draft";

    /** 评审意见（由 ReviewerAgent 产出，String） */
    public static final String REVIEW = "review";

    /** 评审分数（由 ReviewerAgent 产出，Double，0~1） */
    public static final String SCORE = "score";
}
```

**这是整个黑板设计里最重要的一块，逐点解读**：

1. **为什么要常量类？** 假设没有它，PlannerAgent 写 `put("outline", ...)`，WriterAgent 读 `get("outLine", ...)`（大小写手滑）。编译**能通过**，运行时 WriterAgent 读到 null，流水线断了，还极难排查——因为字符串拼写错误编译器不会报错。用常量 `Keys.OUTLINE`，一旦拼错常量名（`Keys.OutLine`），**编译期立刻报错**。这就是"把运行时错误提前到编译期"。

2. **`private Keys()` 私有构造器**：Keys 是纯常量容器，不该被实例化。私有构造器阻止 `new Keys()`。这是"工具类/常量类"的标准写法。

3. **注释即契约文档**：每个常量的注释写明了"谁产出、什么类型"。例如 `OUTLINE` 注释 `List<String>`、`SCORE` 注释 `Double`。这些注释就是黑板的"数据字典"，四个 Agent 严格遵守它，才能"对上暗号"。

4. **`static final`**：编译期常量，全局唯一，零内存开销。

> 💡 **这就是第二章"避坑 2：黑板键要有约定"的完整落地**。在真实项目里，键约定混乱是黑板模式最常见的 bug 来源。集中常量 + 类型注释是最简单有效的治理手段。

---

## 5.3 三个核心读写方法

### 5.3.1 put —— 写入并智能截断日志

```java
public void put(String key, Object value) {
    board.put(key, value);
    log.debug("[SharedMemory] put {} = {}", key,
            value instanceof String s && s.length() > 40 ? s.substring(0, 40) + "..." : value);
}
```

**看点在 debug 日志的"智能截断"**：

- `value instanceof String s`：Java 16+ 的**模式匹配**语法，判断类型的同时直接绑定变量 `s`，省去强转。
- `s.length() > 40 ? s.substring(0, 40) + "..." : value`：如果值是长字符串（比如几千字的 DRAFT 草稿），日志里只打前 40 个字符加省略号。**为什么？** 因为草稿动辄几 KB，全打进日志会刷屏、拖慢、还难看。截断是可观测性的贴心设计——既留了痕迹，又不污染日志。

### 5.3.2 get —— 带类型安全的读取

```java
@SuppressWarnings("unchecked")
public <T> T get(String key, Class<T> type) {
    Object v = board.get(key);
    if (v == null || !type.isInstance(v)) {
        return null;
    }
    return (T) v;
}
```

**这是黑板"类型安全"的核心方法，逐点解读**：

1. **泛型方法 `<T> T get(String, Class<T>)`**：调用方传入期望类型的 `Class` 对象（如 `List.class`），方法返回该类型。调用处写 `List<String> outline = get(Keys.OUTLINE, List.class)`，拿到的直接是 List，无需外部强转。

2. **双重防御 `v == null || !type.isInstance(v)`**：
   - `v == null`：键不存在时返回 null（不抛异常）。
   - `!type.isInstance(v)`：**类型不符时也返回 null**。假设某个 bug 把 SCORE 存成了 String，你用 `get(SCORE, Double.class)` 读，`isInstance` 检测到类型不符，返回 null 而不是抛 `ClassCastException`。这把"运行时崩溃"降级成了"温和的 null"，下游的空值判断就能兜住。

3. **`@SuppressWarnings("unchecked")`**：`(T) v` 强转有泛型警告，但因为前面已经用 `isInstance` 校验过类型安全，这里抑制警告是合理的。

> 💡 **设计哲学**：`get` 方法体现了"永远返回可预期的结果"——要么是正确类型的值，要么是 null，**绝不抛异常、绝不返回错误类型**。这让所有调用方的防御逻辑变得简单统一：只需判 null。

### 5.3.3 getString —— 便捷方法

```java
public String getString(String key) {
    return get(key, String.class);
}
```

因为读字符串（DRAFT、REVIEW）非常频繁，封装一个便捷方法，让调用处从 `get(Keys.DRAFT, String.class)` 简化成 `getString(Keys.DRAFT)`。**这是"为高频用法提供语法糖"的可用性设计**。ReviewerAgent 和 Coordinator 读草稿时都用了它。

---

## 5.4 两个辅助方法：contains 与 dump

### 5.4.1 contains —— 探测键是否存在

```java
public boolean contains(String key) {
    return board.containsKey(key);
}
```

有时候我们只想知道"某数据在不在"，不关心具体值。例如 Coordinator 可以先 `contains(Keys.DRAFT)` 判断草稿是否已产出，再决定后续动作。它比 `get(...) != null` 更语义化、更轻量（不做类型转换）。

### 5.4.2 dump —— 导出黑板只读快照

```java
public Map<String, Object> dump() {
    return Map.copyOf(board);
}
```

**这是"可追溯"红利的技术支撑，逐点解读**：

- `Map.copyOf(board)`：返回黑板内容的**不可变浅拷贝**。为什么不直接返回 `board`？因为直接返回底层 Map，外部就能 `dump().put(...)` 篡改黑板，破坏封装。`Map.copyOf` 生成的是**不可变 Map**，外部拿到只能读、不能改，安全地满足"看一眼当前状态"的需求。
- **典型用途**：调试时打印整块黑板、测试时断言黑板内容、异常时把黑板快照写进日志用于事后分析。

> 💡 **防御式返回**：凡是"对外暴露内部集合"的方法，都应返回不可变副本或只读视图，防止外部意外/恶意修改内部状态。这是封装的进阶实践。

---

## 5.5 深度探讨：为什么不用"强类型黑板"

有同学会问：既然 `Map<String, Object>` 要做类型转换、有 unchecked 警告，为什么不设计一个**强类型黑板**，比如：

```java
// 强类型方案（本项目未采用）
public class TypedMemory {
    private List<String> outline;
    private Map<String,String> materials;
    private String draft;
    private Double score;
    private String review;
    // 各自的 getter/setter...
}
```

**两种方案的取舍对比**：

| 维度 | Map 黑板（本项目） | 强类型黑板 |
| --- | --- | --- |
| 类型安全 | 运行时（isInstance 兜底） | 编译期（最强） |
| 扩展新数据项 | 加个 Keys 常量即可，黑板类不动 | 必须改黑板类加字段 |
| 通用性 | 任何 Agent 存任何数据 | 字段写死，绑定特定业务 |
| 违反 OCP？ | 否（对扩展开放） | 是（加数据要改类） |

**结论**：强类型黑板**编译期最安全**，但**每加一种数据就要改黑板类**，违反开闭原则，且把黑板和具体业务绑死了。Map 黑板牺牲了一点编译期安全（用 `isInstance` + 常量 + 注释补回来大部分），换来了**极强的扩展性和通用性**——这正是通用框架该有的取舍。

**这是一道经典的架构权衡题**：没有绝对的对错，取决于你是在做"一次性业务代码"（选强类型）还是"可复用框架"（选 Map + 约定）。本项目定位是教学用的通用 Multi-Agent 框架，所以选后者。

---

## 5.6 并行化伏笔：ConcurrentHashMap 的深意

V1 是顺序流水线，四个 Agent 一个接一个执行，**根本不存在并发**。那为什么黑板要用线程安全的 `ConcurrentHashMap`？

因为**未来的协同策略会需要并行**。设想一个"并行研究"场景：

```
                    ┌─→ ResearchAgent-A（研究小节1、2）─┐
Planner 产出大纲 ───┤                                    ├─→ 汇总到 MATERIALS ─→ Writer
                    └─→ ResearchAgent-B（研究小节3、4）─┘
```

两个 ResearchAgent 并发往黑板写 MATERIALS，如果黑板底层是普通 `HashMap`，并发写会导致数据错乱甚至死循环（JDK7 的经典问题）。用 `ConcurrentHashMap`，并发写天然安全。

**设计启示**：用 `ConcurrentHashMap` 替代 `HashMap` 几乎零成本（性能差异在本场景可忽略），却提前为并行策略扫清了障碍。这就是"低成本的前瞻性"——不过度设计（没真去实现并行），但为可预见的演进留好后路。

> ⚠️ **注意**：`ConcurrentHashMap` 只保证**单个 put/get 操作**的线程安全。如果未来出现"读-改-写"的复合操作（如"读出 MATERIALS，追加一项，再写回"），仍需额外的同步手段（如 `compute` 原子方法）。线程安全是有边界的，别以为用了并发容器就万事大吉。

---

## 5.7 企业案例：一次因"黑板键拼写"引发的线上事故

某团队的内容平台上线后，偶发"文章没有配图"的问题，排查了三天。最后发现：负责配图的 `ImageAgent` 写黑板用的 key 是 `"image_urls"`，而 `PublishAgent` 读的是 `"imageUrls"`（驼峰 vs 下划线）。因为黑板是 `Map<String,Object>`，拼错的 key 编译期毫无报错，运行时 `PublishAgent` 读到 null，走了"无图发布"分支。

**事后改进**：他们引入了本章的 `Keys` 常量类，把所有黑板键收敛为常量。此后再没发生过同类事故。

**教训**：黑板模式的灵活性是把双刃剑——键的自由度越高，"对不上暗号"的风险越大。**常量约定不是可选项，是必选项**。这个案例是第二章"避坑 2"和本章 5.2.2 的真实注脚。

---

## 5.8 常见问题 FAQ

**Q1：黑板用 `Object` 存值，取的时候要转型，会不会性能差？**
A：`isInstance` 和强转的开销极小（纳秒级），相比 LLM 调用（几百毫秒到几秒）完全可忽略。不必为此纠结。

**Q2：`get` 读不到返回 null，会不会到处判 null 很啰嗦？**
A：确实需要判 null，但这是"显式防御"，比"隐式崩溃"好得多。四个 Agent 的防御式读取（第四章）就是配套设计。你也可以封装 `getOrDefault` 或 `Optional` 版本减少判空样板。

**Q3：dump 返回不可变 Map，那我想调试时临时改一下黑板怎么办？**
A：调试改状态应该走 `put`，而不是改 dump 的结果。dump 的定位就是"只读快照"，职责单一。想改就用正规的 put 通道。

**Q4：多个任务并发进来，会不会共用一块黑板导致串数据？**
A：不会。看 Coordinator 源码——每次 `coordinate(task)` 都 `new SharedMemory()`，**每个任务一块独立黑板**。黑板是任务级的，不是全局单例。这是关键的隔离设计。

---

## 5.9 面试高频题

1. **什么是黑板模式？它解决了什么问题？**
   （参考答案：多个知识源通过共享数据空间协作、互不直接调用的模式。解决了组件间的强耦合问题，带来解耦和可追溯两大红利。）

2. **你的黑板用 Map 存异构数据，如何保证类型安全？**
   （参考答案：Keys 常量约定键、注释约定类型、get(key, Class) 用 isInstance 做运行时类型校验并在类型不符时返回 null 而非抛异常。）

3. **为什么用 ConcurrentHashMap 而不是 HashMap？V1 明明是顺序执行。**
   （参考答案：为未来并行协同策略预留，成本几乎为零；HashMap 并发写会数据错乱。属于低成本前瞻性设计。）

4. **对外暴露内部集合有什么风险？你怎么处理的？**
   （参考答案：外部可篡改内部状态破坏封装。dump 用 Map.copyOf 返回不可变副本，只读不可改。）

5. **Map 黑板和强类型黑板怎么选？**
   （参考答案：从类型安全、扩展性、OCP、通用性四个维度权衡。框架/通用场景选 Map+约定，一次性业务选强类型。）

---

## 5.10 本章练习（含参考答案）

**练习 1**：为 SharedMemory 增加一个 `getOrDefault(String key, Class<T> type, T defaultValue)` 方法，读不到时返回默认值而非 null。

<details><summary>参考答案</summary>

```java
public <T> T getOrDefault(String key, Class<T> type, T defaultValue) {
    T v = get(key, type);
    return v == null ? defaultValue : v;
}
```
这样 WriterAgent 取素材可写成 `getOrDefault(Keys.MATERIALS, Map.class, Map.of())`，省去判空。
</details>

**练习 2**：`dump()` 用了 `Map.copyOf`。如果 DRAFT 是一个可变对象（比如 `StringBuilder`），`Map.copyOf` 能防止外部改它的内容吗？为什么？

<details><summary>参考答案</summary>

**不能**。`Map.copyOf` 是**浅拷贝**——它只复制 Map 结构，不复制 value 对象本身。如果 value 是可变对象，外部拿到 dump 后仍能调用它的方法改内部状态。要彻底防御需深拷贝，但成本高。本项目黑板存的值多为不可变（String/List.of/Double），所以浅拷贝足够。
</details>

**练习 3**：假设要支持"并行研究"，两个 ResearchAgent 分别处理不同小节，都要往 MATERIALS（一个 Map）里追加数据。直接各自 `get MATERIALS → put(section, x) → put回` 会有什么问题？如何修复？

<details><summary>参考答案</summary>

会有**竞态条件**：两个线程"读-改-写"同一个 MATERIALS Map，可能互相覆盖对方的写入。`ConcurrentHashMap` 只保证单次操作原子，不保证复合操作。修复方案：① 让每个 ResearchAgent 写各自独立的 key（如 MATERIALS_A、MATERIALS_B），最后由汇总步骤合并；② 或直接把 MATERIALS 本身设为 ConcurrentHashMap，各线程直接往里 put 各自小节（因为 key 不冲突）。方案②更简洁。
</details>

---

## 5.11 本章任务

> ✅ **动手清单**（对应代码：`agent/memory/SharedMemory.java`）

1. 通读 `SharedMemory.java`，画出它的方法清单：put / get / getString / contains / dump。
2. 找出 `Keys` 常量类，对照第四章四个 Agent 的读写，验证键与类型是否严格对齐。
3. 理解 `get` 方法里 `!type.isInstance(v)` 这一行的防御意义——尝试想象去掉它会发生什么。
4. 理解 `put` 里 debug 日志的字符串截断逻辑，思考它对生产日志的价值。
5. 完成练习 1，为黑板加 `getOrDefault` 方法，用 `mvn compile` 验证编译通过。
6. **挑战题**：在 Coordinator 成功分支的末尾，加一行 `log.debug("最终黑板：{}", memory.dump())`，运行时观察一次完整协作后黑板上都有什么。

**下一章预告**：第六章我们将剖析 **Coordinator 调度中心** 与 **AgentManager 花名册**，看它们如何用状态机思想编排流水线、用 Spring 自动装配实现"零改动扩展"，并展望与 Workflow/MCP 的整合方向。