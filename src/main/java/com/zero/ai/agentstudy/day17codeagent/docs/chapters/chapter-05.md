# Chapter 05 · 静态分析与仓库地图：零依赖读懂一个仓库

> 本章目标：用零第三方依赖的方式把仓库变成「符号表 + 依赖图 + 一页索引卡（RepoMap）」，让模型在没读完全部代码的情况下就知道「该改哪个文件、改完会影响谁」。

---

## 一、为什么要学（Why）

有了工具（Ch04），模型能 `list_files` 和 `read_file` 了。但真实仓库有几千个文件，全塞进上下文会立刻爆掉。更糟的是：

- **它不知道该读哪个文件**：需求说「给 Money 增加乘法」，它得先知道有 `Money` 这个类、在哪个文件、第几行；
- **它不知道改完会影响谁**：改了 `Money` 的构造器，谁会编译失败？它得一并把依赖方也读进来。

这两件事都需要**代码理解能力**——把源码变成结构化的符号与依赖关系。

还有一个隐蔽但致命的问题：模型给的需求可能是「把 TODO 里的 `ghost` 方法实现一下」，而 `ghost` 只出现在**注释和字符串**里。如果解析器不做清洗，符号表里会多出一堆**永远找不到的幽灵符号**，模型就会去「实现一个不存在的方法」，然后陷入死循环。

---

## 二、是什么（What）

### 2.1 三个产物

| 产物 | 回答的问题 | 消费方 |
| --- | --- | --- |
| **符号表**（`SymbolTable`） | 这个类/方法在哪、在第几行 | 上下文选择器（Ch06）精确定位 |
| **依赖图**（`DependencyGraph`） | 改了 A，谁会受影响 | 影响面计算 → 一并纳入上下文 |
| **RepoMap** | 这个仓库长什么样（一页纸） | 直接拼进提示词，给模型全局感 |

### 2.2 为什么「等长空格替换」是关键

清洗注释与字符串时，不能简单地删除——否则**行号会错位**，后续所有「第 29 行报错」的定位全部失效。正确做法是替换为**等长空格**：

```java
// 真实输出里的清洗效果（Chapter 05 Demo）
  【清洗后的文本】（保留换行、内容变空格，因此行号不偏移）
    |package demo.trap;|
    ||
    |public class RealClass {|
    |                                    |
    |                            |
    |    private String tip =                                                  ;|
```

注释和字符串变成了空格，但**行数与列偏移完全没变**。

---

## 三、怎么用（How）

> 运行：`java -Dstdout.encoding=UTF-8 -cp target/classes com.zero.ai.agentstudy.day17codeagent.analysis.Chapter05Demo`

### 3.1 解析出符号清单

```
=== [1] 解析 Money.java ===
  package = demo.order
  imports = [java.math.BigDecimal]
  符号清单：
    class       Money          class Money                  L8
    field       amount         BigDecimal amount            L10
    ctor        Money          Money(BigDecimal)            L12
    method      of             Money of(long)               L16
    method      zero           Money zero()                 L20
    method      add            Money add(Money)             L24
    method      greaterThan    boolean greaterThan(Money)   L28
    method      amount         BigDecimal amount()          L32
```

每一行都是「类型 + 名字 + 签名 + 行号」——**行号是关键**，它让后续的上下文选择、补丁定位、评审意见都能精确到行。

### 3.2 幽灵符号陷阱

Demo 里有一段刻意构造的「陷阱源码」：注释里写了 `class CommentService`、字符串里写了 `"class StringService {"`、TODO 里提到 `ghost` 方法。真实输出：

```
=== [2] 注释与字符串里的假代码 ===
  解析出的类型级符号：
    class RealClass
  解析出的方法：
    void real()
  → 若不做清洗，这里会多出 CommentService / StringService / ghost 三个幽灵符号
```

**如果符号表里混进幽灵符号**，模型会尝试去 `read_file` 一个不存在的文件、去实现一个不存在的方法，然后反复失败。清洗这一步的收益远比看起来大。

### 3.3 符号表：精确查 + 模糊查

```
=== [3] 符号表：精确查 + 模糊查 ===
  文件数 = 4  符号总数 = 19
  精确查 "discountFor" → [method discountFor @ src/main/java/demo/order/DiscountPolicy.java:10]
  模糊查 "Order" →
      record Order @ src/main/java/demo/order/OrderRepository.java:14
      interface OrderRepository @ src/main/java/demo/order/OrderRepository.java:8
      class OrderService @ src/main/java/demo/order/OrderService.java:6
      ctor OrderService @ src/main/java/demo/order/OrderService.java:11
```

模糊查按「相等 > 前缀 > 子串」打分排序。为什么需要模糊查？因为**需求里的用词和代码里的名字往往不完全一致**——需求说「下单流程」，代码叫 `checkout`。

### 3.4 依赖图与影响面

```
=== [4] 依赖图：谁引用了谁 ===
  节点数 = 4  边数 = 3
    Money.java                                     依赖 []
    OrderRepository.java                           依赖 []
    OrderService.java                              依赖 [DiscountPolicy.java, OrderRepository.java, Money.java]
    DiscountPolicy.java                            依赖 []
  反向：用到 Money 的文件 = [src/main/java/demo/order/OrderService.java]
  是否存在环 = false

  【影响面】改动 Money.java 时需要一并检查：
      src/main/java/demo/order/OrderService.java
      src/main/java/demo/order/Money.java
```

依赖图用**三遍扫描**构建：

1. 建立「简单类名 → 文件」索引；
2. 解析 `import`（含 `*` 通配）建立跨包边；
3. **扫描同包引用**——这一步最容易被漏掉：同包的类之间没有 `import`，只做前两遍会得到一张空图（Demo 的仓库就是这样，最初边数是 0）。

`impactSet`（影响面）= 反向依赖闭包。它的用途非常直接：**改了 `Money`，就把 `OrderService` 也放进上下文**，否则模型改完 `Money` 的签名却不知道 `OrderService` 会编译失败。

同时提供 `hasCycle()`——循环依赖的仓库在打包时会出问题，早发现早处理。

### 3.5 RepoMap：给模型的一页索引卡

```
=== [5] RepoMap：给 LLM 的一页索引卡 ===
src/main/java/demo/order/DiscountPolicy.java (20 行)
  public class DiscountPolicy
    - VIP_THRESHOLD :long  @L8
    + discountFor(long, boolean) :double  @L10

src/main/java/demo/order/Money.java (36 行)
  public final class Money
    - amount :BigDecimal  @L10
    - Money(BigDecimal) :ctor  @L12
    + of(long) :Money  @L16
    + zero() :Money  @L20
    + add(Money) :Money  @L24
    + greaterThan(Money) :boolean  @L28
    + amount() :BigDecimal  @L32

...

  文件数 = 4  总行数 = 102  估算 token ≈ 552

  RepoMap 只占 552 token，而五个文件原文共 102 行 —— 这就是「先给索引卡，再按需取原文」的价值
```

几个设计细节：

- **`+` / `-` 表示可见性**：模型一眼能看出哪些是可调用的公开方法；
- **只放签名不放实现**：这才是「一页纸」的关键；
- **只展示 `L` 行号不带列**：够用且省 token；
- **估算 token**：让 Ch06 的预算计算有据可依。

> 552 token 换来「模型知道整个仓库长什么样」，这笔账非常划算——而如果把 102 行原文全塞进去，光这一个迷你仓库就要上千 token，真实仓库根本塞不下。

---

## 四、真实项目中的应用

| 课程实现 | 生产替换 | 备注 |
| --- | --- | --- |
| 正则级 `LightweightJavaParser` | JavaParser / javac `Trees` API | 课程版不处理深层泛型、注解换行、多行 Lambda |
| 单文件解析 | 全仓库并发解析 + 缓存 | 首次建图后可缓存，按 git commit 失效 |
| 影响面 = 反向依赖闭包 | 加上调用链分析（谁调用了这个方法） | 依赖图是「编译级」影响，调用链是「语义级」 |
| RepoMap 全量 | 大仓库只放「与需求相关的 N 个文件」+ 目录树 | 见 Ch06 的选择与降级 |

一个真实经验：**RepoMap 应该包含目录结构**。真实仓库有几千个文件时，光是目录树就能给模型极强的定位能力——本课程仓库太小所以省略了，生产实现建议补上。

---

## 五、避坑与优化

### 坑 1：清洗时直接删除注释与字符串
**后果**：行号错位，「第 29 行缺分号」这样的定位全部失效。
**做法**：替换为**等长空格**，保留换行与列偏移。

### 坑 2：依赖图只解析 import
**后果**：同包引用全部丢失，边数为 0，影响面算不出来。
**做法**：第三遍扫描同包类名引用（Demo 里修正后边数从 0 变成 3）。

### 坑 3：把 record 的紧凑构造器当成方法
**后果**：`record Order(String, long, boolean)` 会同时命中「类型正则」和「方法正则」，符号表里多出一个幽灵 `method Order`。
**做法**：用 `typeMatched` 标志，命中类型声明时跳过本行的 METHOD 匹配。

### 坑 4：返回类型取错位置
**后果**：`returnTypeOf` 取最后空格**之后**的内容，把方法名当成了返回类型（`of(long) :of`）。
**做法**：取最后空格**之前**为返回类型，构造器特判为 `ctor`。

### 坑 5：把正则解析器当生产级用
**提醒**：它不处理深层嵌套泛型、注解换行、多行 Lambda。价值是「零依赖跑通教学链路」，**不要直接上生产**。

### 优化建议
- **缓存 RepoMap**：按仓库 commit 缓存，避免每个任务都重新解析。
- **模糊查要限量**：需求词很短时（如「a」）会命中上百个符号，必须限量。

---

## 本章小结

- 代码理解三件套：**符号表**（在哪）、**依赖图**（影响谁）、**RepoMap**（长什么样）。
- 清洗注释与字符串必须**等长空格替换**，否则行号错位，后续全部定位失效；这一步还顺带消灭了「幽灵符号」。
- 依赖图要**三遍扫描**（索引 → import → 同包引用），只做 import 会得到空图。
- `impactSet` 反向依赖闭包决定「改了 A 要把谁一起放进上下文」。
- RepoMap 用 552 token 让模型获得全局感，是「先索引、后原文」策略的核心。

---

> **下一章预告**：Chapter 06 进入**上下文工程**。有了 RepoMap 和符号表，我们还得回答一个现实问题：窗口就那么大，到底该把哪些文件的**全文**、哪些的**签名**、哪些干脆**丢掉**？
>
> 👉 输入「继续」进入 Chapter 06。
