# 第五章 命令系统（Command System）

> 上一章我们让消息「找到对的处理器」，其中最高优先级的 `CommandHandler` 只是一个占位。
> 本章把它升级为一套**注解式、可自扩展、生产级的命令系统**——这是 AI Agent「确定性能力」的骨架。

---

## 一、为什么学：Agent 不能只有「猜」，还要有「确定性能力」

### 1.1 一个真实的产品事故

某企业 AI 助手上线首周，产品经理提了个「小需求」：

> 用户发 `重置` 两个字，就把对话上下文清空重来。

工程师顺手在闲聊处理器里加了一行：

```java
if (content.contains("重置")) {
    session.clear();
    return reply("已重置");
}
```

三天后线上炸了。有用户在讨论「如何**重置**路由器密码」，助手直接把整段客服对话上下文清空了；还有人问「这个功能什么时候**重置**」，同样中招。

**根因**：自然语言是「模糊的」，而「清空上下文」是一个**不可逆、需要精确触发**的操作。把确定性操作交给模糊匹配，等于把保险柜密码设成「差不多就行」。

### 1.2 LLM 擅长「模糊」，但企业需要「确定」

大模型的强项是理解模糊意图、生成自然回复。但一个企业级 Agent 一定同时存在两类能力：

| 能力类型 | 触发方式 | 结果特征 | 典型场景 |
| --- | --- | --- | --- |
| 模糊能力（LLM） | 自然语言 | 每次可能不同、需容错 | 闲聊、问答、总结、翻译 |
| 确定能力（命令） | 精确指令 | 每次一致、可预测、可审计 | 重置会话、查身份、订会议室、发通知、管理员操作 |

**确定能力**必须满足：
- **精确触发**——只有 `/reset` 才重置，绝不因「重置」二字误伤；
- **可预测**——同样的输入永远同样的行为；
- **可审计**——谁在什么时候执行了什么命令，有据可查；
- **可自描述**——用户能通过 `/help` 知道有哪些能力、怎么用。

这正是「命令系统」的价值：它给 Agent 装上一套**确定性的操作面板**，与 LLM 的模糊能力互补。

### 1.3 类比：命令行 CLI 与斜杠命令

你一定用过 `git commit -m "msg"`、`docker run -d nginx`。命令行的精妙在于：
- 一个**命令名**（`commit`）决定「做什么」；
- 一串**参数**（`-m "msg"`）决定「怎么做」；
- 一份**帮助**（`git --help`）让人自学。

现代 IM（Slack、Discord、飞书、企微）里的「斜杠命令」`/xxx` 是同一套心智模型。我们要在 Agent 里复刻这套机制：让 `/book room=301 time=14:00 项目评审` 被**结构化解析**、**精确路由**到订会议室的能力上。

---

## 二、是什么：命令系统的四个核心构件

命令系统把「一段文本」变成「一次确定执行」，拆成四个协作的构件：

```
用户输入 "/book room=301 time=14:00 项目评审"
        |
        v
  +-------------------+
  |  CommandParser    |  解析：命令名 + 键值参数 + 自由文本
  +-------------------+
        |  ParsedCommand{ name=book, options={room:301,time:14:00}, argument="项目评审" }
        v
  +-------------------+
  |  CommandRegistry  |  查表：name -> Command（构造期自动收集全部命令）
  +-------------------+
        |  Command 实例（如 BookCommand）
        v
  +-------------------+
  | CommandDispatcher |  编排：构造上下文 -> execute -> 异常兜底 -> CommandResult
  +-------------------+
        |  CommandResult{ status=OK, message="已预订..." }
        v
     对外回复（Markdown / 文本 / 静默）
```

### 2.1 四个构件的职责

| 构件 | 层次 | 职责 | 关键设计 |
| --- | --- | --- | --- |
| `CommandParser` | application | 把文本解析成 `ParsedCommand` | 纯函数、无状态、线程安全 |
| `CommandRegistry` | application | 收集所有 `Command`，建 name/alias→Command 索引 | 构造期集合注入、不可变、冲突快速失败 |
| `CommandDispatcher` | application | 串联解析→查表→执行→兜底 | 统一异常处理、未知命令引导 `/help` |
| `Command`（端口） | domain/port | 每个命令的统一契约 | 领域端口不依赖 Spring、策略模式 |

### 2.2 领域模型：三个值对象

- **`ParsedCommand`**：解析结果。`name`（命令名，小写）+ `options`（键值参数只读 Map）+ `argument`（自由文本）+ `raw`（原文备查）。
- **`CommandContext`**：执行上下文。把 `ParsedCommand` 与身份信息（`tenantId`/`fromUserId`/`conversationId`/`channelType`）+ 原始 `InboundMessage` 打包，命令实现只依赖它，不碰传输层。
- **`CommandResult`**：执行结果三态。`OK`（有 Markdown 回复）/`SILENT`（已处理无回复）/`ERROR`（失败提示）。

### 2.3 为什么用「注解 / 集合注入」而不是「switch-case」

回想第四章的路由器：新增意图无需改路由器。命令系统沿用同一招——**开闭原则（OCP）**：

```
新增一个命令  =  新增一个 @Component implements Command
             =  CommandRegistry 自动收集
             =  /help 自动更新
             =  零改动分发器、零改动注册代码
```

对比「上帝 switch」的反面教材：

```java
// 反面教材：每加一个命令就要改这里，改坏一处全崩
switch (cmd) {
    case "help":  return help();
    case "reset": return reset();
    case "book":  return book();  // 第 50 个 case 时你会崩溃
    // ...
}
```

集合注入版把「命令定义」与「命令调度」彻底解耦：调度器永远不认识具体命令，只认识 `Command` 接口。这就是**策略模式 + 依赖倒置**的威力。

### 2.4 命令系统与责任链的关系

命令系统不是独立入口，它挂在第四章责任链的 `CommandHandler` 下面：

```
MessageRouter (责任链)
   -> CommandHandler (order=10, supports: 文本且以 / 开头)
        -> CommandDispatcher (命令系统入口)
             -> CommandParser / CommandRegistry / Command.execute
   -> MultimodalHandler (order=20)
   -> ChatHandler (order=30)
   -> EchoHandler (order=MAX)
```

**分工清晰**：责任链负责「这条消息是不是命令」，命令系统负责「是哪个命令、怎么执行」。两者通过 `CommandDispatcher` 这个窄接口衔接。

---

## 三、怎么用：核心代码逐段解析

### 3.1 `Command` 端口——每个命令的统一契约

```java
public interface Command {
    String name();                          // 唯一键，不含前缀，小写
    default String[] aliases() { return new String[0]; }  // 别名，如 /help 的 /? /menu
    String description();                   // 一行用途，供 /help 展示
    default String usage() { return "/" + name(); }        // 用法示例
    CommandResult execute(CommandContext context);         // 执行
}
```

四点设计要义：
1. **领域端口不依赖 Spring**——接口本身零注解，实现类才加 `@Component`，保持六边形架构纯净。
2. **`name()` 是唯一键**——注册表以它建索引；`aliases()` 提供备用触发词。
3. **`description()`/`usage()` 是「自描述」**——让 `/help` 能自动生成帮助，无需单独维护文档。
4. **`execute` 返回 `CommandResult` 而非抛异常**——可预期错误（参数非法、无权限）用 `CommandResult.error(...)` 表达，给用户友好提示。

### 3.2 `ParsedCommand` + `CommandParser`——把文本变结构

解析器把 `/book room=301 time=14:00 项目评审` 拆成结构化对象：

```java
public ParsedCommand parse(String text) {
    String raw = text == null ? "" : text.strip();
    if (!raw.startsWith(PREFIX)) return new ParsedCommand("", Map.of(), "", raw);

    String body = raw.substring(PREFIX.length()).strip();   // 去掉前缀 /
    if (body.isEmpty()) return new ParsedCommand("", Map.of(), "", raw);

    String[] tokens = body.split("\\s+");
    String name = tokens[0];                                // 第一个 token 是命令名

    Map<String, String> options = new LinkedHashMap<>();
    StringBuilder argument = new StringBuilder();
    for (int i = 1; i < tokens.length; i++) {
        String token = tokens[i];
        int eq = token.indexOf('=');
        if (eq > 0) {                                       // key=value 形式
            String key = token.substring(0, eq).strip();
            String value = token.substring(eq + 1).strip();
            if (!key.isEmpty()) { options.put(key, value); continue; }
        }
        if (!argument.isEmpty()) argument.append(' ');       // 其余归入自由文本
        argument.append(token);
    }
    return new ParsedCommand(name, options, argument.toString(), raw);
}
```

解析规则：**第一个 token=命令名**，其后 `key=value` 进 `options`，剩余的拼成 `argument`。这样 `/book room=301 time=14:00 项目评审` 会被解析为 `name=book`、`options={room:301, time:14:00}`、`argument="项目评审"`。

`ParsedCommand` 本身是不可变值对象，`options` 在构造期被包成只读视图，并提供 `option(key, default)`、`hasOption(key)` 等便捷读取方法。

### 3.3 `CommandRegistry`——构造期自动收集 + 冲突快速失败

```java
public CommandRegistry(List<Command> commands) {          // 集合注入：Spring 送来所有命令
    Map<String, Command> idx = new LinkedHashMap<>();
    Map<String, Command> byName = new TreeMap<>();        // 字典序，供 /help
    for (Command command : commands) {
        String name = normalize(command.name());
        if (name.isEmpty()) throw new IllegalStateException("命令名不可为空...");
        register(idx, name, command);                     // 注册主名
        byName.put(name, command);
        for (String alias : command.aliases()) register(idx, normalize(alias), command);
    }
    this.index = Map.copyOf(idx);                         // 构造期固化为不可变
    this.commandsByName = Collections.unmodifiableMap(byName);
}

private void register(Map<String, Command> idx, String key, Command command) {
    Command existing = idx.putIfAbsent(key, command);
    if (existing != null && existing != command)          // 冲突：两个命令抢同一个名
        throw new IllegalStateException("命令名/别名冲突：'" + key + "'...");
}
```

三个关键点：
- **集合注入**：构造参数 `List<Command>` 会被 Spring 自动填充为容器里所有 `Command` Bean，这就是「新增命令零配置」的机关。
- **冲突快速失败**：两个命令抢同一个 `name`/`alias`，直接在启动期抛异常，绝不拖到运行期才发现歧义。
- **构造期固化**：索引一次建成、`Map.copyOf` 转不可变，运行期只读、无锁、线程安全——与第四章 `MessageRouter` 构造期排序同一套思路。

### 3.4 `CommandDispatcher`——解析→查表→执行→兜底

```java
public CommandResult dispatch(InboundMessage message, String text) {
    ParsedCommand parsed = parser.parse(text);
    if (parsed.name().isEmpty())
        return CommandResult.error("请输入有效命令，例如 `/help`...");

    Optional<Command> found = registry.find(parsed.name());
    if (found.isEmpty())                                  // 未知命令 -> 引导 /help
        return CommandResult.error("未知命令：`/" + parsed.name() + "`。发送 `/help`...");

    Command command = found.get();
    CommandContext context = CommandContext.of(message, parsed);
    long start = System.nanoTime();
    try {
        CommandResult result = command.execute(context);
        log.info("[Command] 执行 /{} status={} cost={}ms tenant={}",
                command.name(), result.status(), (System.nanoTime()-start)/1_000_000, context.tenantId());
        return result == null ? CommandResult.silent() : result;
    } catch (Exception ex) {                              // 统一兜底：一条坏命令不拖垮 Agent
        log.error("[Command] 执行 /{} 异常 tenant={}", command.name(), context.tenantId(), ex);
        return CommandResult.error("命令执行出错：" + safeMessage(ex) + "...");
    }
}
```

分发器是命令系统的「总闸」，四个职责：解析、查表、执行、**统一异常兜底**。任何命令抛出的未捕获异常都被转成 `CommandResult.error(...)`——保证「一条坏命令不会拖垮整个 Agent」。同时埋了执行耗时与租户日志，天然可观测。

### 3.5 `HelpCommand`——遍历注册表，帮助「自动生成」

```java
@Component
public class HelpCommand implements Command {
    private final CommandRegistry registry;
    public HelpCommand(@Lazy CommandRegistry registry) { this.registry = registry; }  // @Lazy 破循环

    @Override public String name() { return "help"; }
    @Override public String[] aliases() { return new String[]{"?", "menu"}; }
    @Override public String description() { return "查看所有可用命令"; }

    @Override public CommandResult execute(CommandContext context) {
        StringBuilder sb = new StringBuilder("### 可用命令（共 " + registry.size() + " 条）\n\n");
        for (Command command : registry.all()) {          // 遍历全部命令，自动成表
            sb.append("- `/").append(command.name()).append("` — ")
              .append(command.description()).append('\n')
              .append("  用法：`").append(command.usage()).append("`\n");
        }
        return CommandResult.ok(sb.toString());
    }
}
```

**帮助自动生成**是命令系统的点睛之笔：新增任何命令，`/help` 立刻反映出来，永不「文档与实现脱节」。

注意 `@Lazy`：`CommandRegistry` 构造期要收集所有 `Command`（含 `HelpCommand`），而 `HelpCommand` 又依赖注册表——存在构造期循环依赖。`@Lazy` 让注册表以代理形式先注入，首次真正调用时才解析，优雅破环。

### 3.6 内置命令一览

| 命令 | 别名 | 依赖 | 作用 |
| --- | --- | --- | --- |
| `/help` | `/?` `/menu` | CommandRegistry | 遍历注册表自动生成帮助菜单 |
| `/reset` | `/clear` `/new` | SessionService | 删除旧会话并重建，清空多轮上下文 |
| `/whoami` | `/me` | 无（读上下文） | 回显租户/用户/渠道/会话身份 |

`/reset` 复用了第三章 `SessionService`，为此我们给它补了一个 `reset(tenantId, channelType, conversationId)` 方法：删除旧会话 key，重建一个全新活跃会话——确定性地「重开一局」。

### 3.7 责任链入口升级——`CommandHandler` 从占位到真身

```java
@Component
public class CommandHandler implements MessageHandler {
    private final CommandDispatcher commandDispatcher;    // 注入命令系统入口
    public CommandHandler(CommandDispatcher d) { this.commandDispatcher = d; }

    @Override public int order() { return 10; }           // 责任链最高优先级
    @Override public boolean supports(InboundMessage m) {
        return m.getMessageType() == MessageType.TEXT
            && commandDispatcher.isCommand(m.getContent()); // 文本且以 / 开头
    }
    @Override public HandleResult handle(InboundMessage message) {
        CommandResult result = commandDispatcher.dispatch(message);
        return switch (result.status()) {                 // 三态转对外回复
            case OK    -> HandleResult.replied(OutboundMessage.markdownReply(message, result.message()));
            case ERROR -> HandleResult.replied(OutboundMessage.textReply(message, result.message()));
            case SILENT-> HandleResult.handledSilently();
        };
    }
}
```

这就是第四章埋下的「占位」被真正激活的时刻：责任链判断「是命令」，命令系统负责「怎么执行」，`switch` 把 `CommandResult` 三态桥接回 `HandleResult` 三态。层层清晰、各司其职。

---

## 四、真实项目：四条命令的完整旅程

### 旅程一：`/help` —— 自描述的力量

```
用户: /help
  -> CommandHandler.supports: TEXT 且以 / 开头  -> true
  -> CommandDispatcher.dispatch
       -> parse("/help") = ParsedCommand{name=help, options={}, argument=""}
       -> registry.find("help") = HelpCommand
       -> HelpCommand.execute: 遍历 registry.all() = [help, reset, whoami]
       -> CommandResult.ok("### 可用命令（共 3 条）\n- /help — ...\n- /reset — ...\n- /whoami — ...")
  -> HandleResult.replied(markdownReply)
回复: 一份自动生成、永不过期的帮助菜单
```

要点：用户新装了 Agent，第一件事就是 `/help`。它不需要任何人写文档，帮助内容永远等于「当前真实可用的命令集合」。

### 旅程二：`/reset` —— 确定性的不可逆操作

```
用户: /reset
  -> CommandDispatcher.dispatch
       -> parse = ParsedCommand{name=reset}
       -> registry.find("reset") = ResetCommand
       -> ResetCommand.execute
            -> sessionService.reset(tenant, channel, convId)
                 -> repository.delete(oldKey)      // 删旧会话
                 -> ConversationSession.create(...) // 建新会话
                 -> repository.save(fresh)
            -> CommandResult.ok("会话上下文已重置...")
  -> HandleResult.replied
回复: "会话上下文已重置，我们可以开始新的对话啦～"
```

要点：对比 §1.1 的事故，这里**只有精确的 `/reset` 才会触发重置**。用户聊到「重置」二字毫发无伤——确定能力必须精确触发。

### 旅程三：`/whoami` —— 读上下文，零外部依赖

```
用户: /whoami
  -> registry.find("whoami") = WhoAmICommand
  -> WhoAmICommand.execute: 直接读 CommandContext 的身份字段
  -> CommandResult.ok("### 当前身份\n- 租户: ...\n- 用户: ...\n- 渠道: ...\n- 会话: ...")
回复: 身份卡片（用户画像模块接入后可扩充）
```

要点：`CommandContext` 已把身份信息打包好，命令实现零外部依赖即可运行。后续接入用户画像/Memory 后，只需在这一个类里补充，其余不动。

### 旅程四：`/unknown` 与 `/book room=301`（异常与扩展）

```
用户: /unknown
  -> registry.find("unknown") = empty
  -> CommandResult.error("未知命令：/unknown。发送 /help 查看所有可用命令。")
回复: 友好引导，而非冷冰冰的 500

未来扩展 —— 用户: /book room=301 time=14:00 项目评审
  -> 只需新增 @Component class BookCommand implements Command
  -> parse 自动得到 options={room:301,time:14:00}, argument="项目评审"
  -> registry 自动收录, /help 自动出现, dispatcher 自动路由
  -> 零改动任何既有代码
```

### 旅程小结

| 输入 | 命中命令 | 结果状态 | 回复形态 | 体现的设计 |
| --- | --- | --- | --- | --- |
| `/help` | HelpCommand | OK | Markdown 菜单 | 自描述 / 自动生成 |
| `/reset` | ResetCommand | OK | 文本确认 | 确定性触发 / 复用会话服务 |
| `/whoami` | WhoAmICommand | OK | 身份卡片 | 上下文打包 / 零依赖 |
| `/unknown` | 无 | ERROR | 友好引导 | 未知兜底 / 引导 help |
| `/book ...` | BookCommand（未来） | OK | 业务回复 | OCP / 零改动扩展 |

---

## 五、避坑指南（生产血泪 12 条）

1. **别用 `contains("重置")` 做确定操作**。模糊匹配会误伤正常对话。确定能力一律走精确的 `/命令` 前缀，这是命令系统存在的根本理由。

2. **命令名统一小写、`strip()` 去空白**。用户可能发 `/Help`、`/ help`、`/HELP`。解析器与注册表都要 `normalize`，否则同一命令因大小写/空白匹配不到。

3. **注册期就检测命令名冲突，别拖到运行期**。两个命令抢 `/reset`，若不在启动期快速失败，线上会随机命中其中一个，极难排查。

4. **`execute` 里的异常必须在 dispatcher 统一兜底**。任何命令抛未捕获异常，都要转成 `CommandResult.error`，否则一条坏命令会让整个消息处理链崩溃。

5. **`/help` 与 `CommandRegistry` 的循环依赖用 `@Lazy` 破**。注册表构造期收集所有命令（含 help），help 又依赖注册表。忘了 `@Lazy` 会启动失败。

6. **可预期错误返回 `error`，不要 `throw`**。参数缺失、无权限是「业务结果」不是「系统异常」。用 `CommandResult.error(...)` 给用户友好提示，日志级别也不该是 error。

7. **参数解析要容忍 `key=` 空值与 `=value` 缺键**。用 `indexOf('=') > 0` 而非 `>= 0`，避免把 `=abc` 误解析成空键；`key=` 允许空值但键必须非空。

8. **`ParsedCommand.options` 必须不可变**。构造期包成只读 Map，防止命令实现意外修改共享状态，尤其在并发下。

9. **别让命令直接依赖传输层 / 路由层**。命令只依赖 `CommandContext`（领域内），保持可单元测试、可跨渠道复用。上下文用 `of(inbound, parsed)` 工厂集中构造。

10. **危险命令（`/reset`、管理员操作）要预留权限校验位**。当前 `CommandContext` 已带 `fromUserId`/`tenantId`，接入权限模块后在 `execute` 开头校验，不通过就 `error`。

11. **命令回复区分 Markdown 与纯文本**。`OK` 用 `markdownReply`（帮助菜单需要格式），`ERROR` 用 `textReply`（错误提示无需格式），避免错误信息被 Markdown 渲染搞乱。

12. **`SILENT` 状态别忘了处理**。有些命令执行成功但无需回复。`HandleResult.handledSilently()` 表示「已认领、不回复、终止责任链」，别让它掉进兜底 echo。

---

## 六、面试题精选

**Q1：命令系统相比一个巨大的 `switch-case` 有什么本质区别？**

A：`switch-case` 是**封闭**的——每加一个命令都要改动同一个方法，违反开闭原则，且所有命令逻辑耦合在一个类里。命令系统把每个命令做成独立 `Command` 实现，通过集合注入自动收录，新增命令**零改动既有代码**（OCP）；同时每个命令可独立测试、独立部署演进（SRP + 策略模式）。`switch` 还会随命令增多而膨胀成「上帝方法」，命令系统则天然分散。

**Q2：`CommandRegistry(List<Command>)` 是怎么自动拿到所有命令的？**

A：这是 Spring 的**集合注入**。容器扫描到所有实现 `Command` 且标注 `@Component` 的 Bean，在构造 `CommandRegistry` 时把它们打包成 `List` 注入。注册表在构造期遍历这个列表，建立 `name/alias -> Command` 索引并用 `Map.copyOf` 固化为不可变，运行期只读、无锁、线程安全。新增命令只要加一个 `@Component` 类即可被自动收录。

**Q3：`HelpCommand` 依赖 `CommandRegistry`，而 `Registry` 又收集 `HelpCommand`，为何不会启动失败？**

A：这是典型的**循环依赖**。解法是在 `HelpCommand` 注入 `CommandRegistry` 的字段/参数上加 `@Lazy`，Spring 会注入一个**代理**而非真实 Bean，真正调用 `registry.all()` 时才解析目标。这样构造期 `Registry` 收集 `HelpCommand` 不需要 `HelpCommand` 先完全初始化其 `Registry` 依赖，环被打破。构造期只是拿到代理引用，无实际调用。

**Q4：命令系统和第四章的责任链（消息路由）是什么分工关系？**

A：责任链解决「**这条消息该谁处理**」（意图分诊），命令系统解决「**这个命令具体怎么执行**」。`CommandHandler` 是责任链上的一环（order=10，优先级最高），它只判断「是不是命令」，是则委托 `CommandDispatcher`。两者通过 `CommandResult`（三态）→ `HandleResult`（三态）桥接。责任链是**横向**的处理器竞争，命令系统是**纵向**的命令分发，层次分明、各司其职。

---

## 七、小结

| 构件 | 角色 | 关键设计 |
| --- | --- | --- |
| `CommandParser` | 解析 | 纯函数，`/cmd key=value 文本` -> `ParsedCommand` |
| `Command` | 端口契约 | 领域纯净、零 Spring 注解、自描述 |
| `CommandRegistry` | 注册查表 | 集合注入 + 构造期固化 + 冲突快速失败 |
| `CommandDispatcher` | 编排 | 解析->查表->执行 + 统一异常兜底 |
| `CommandResult` | 结果 | OK/SILENT/ERROR 三态，桥接责任链 |
| 内置命令 | 能力 | help 自描述 / reset复用会话 / whoami 身份 |

命令系统是 Agent 的「确定性能力」骨架：**精确触发、可预测、可审计、可自描述、零配置扩展**，与 LLM 的模糊能力形成互补。

## 八、扩展阅读

- 《设计模式》命令模式（Command Pattern）与策略模式（Strategy Pattern）
- Spring 官方文档：集合注入（Autowiring collections）与 `@Lazy` 循环依赖处理
- 《Clean Architecture》端口与适配器（Ports & Adapters）
- Unix 哲学：一个命令只做一件事并做好（Do One Thing Well）
- Slack / Discord Slash Command 设计规范（业界命令系统参考）

## 九、下一章预告

命令系统让 Agent 拥有了「确定性能力」，但真正的智能对话需要**记忆**。第六章《Memory 与 RAG：让 Agent 记住一切》将带你实现 `wechat-memory` 模块：多轮对话记忆（短期记忆）、企业知识库（长期记忆）、基于 pgvector 的向量检索与 RAG 增强。我们将解决「Agent 如何记住上下文」「如何从海量企业文档中检索相关知识」两大核心问题，让 Agent 从「能对话」进化到「懂业务」。
