# 第三章：用 Java 实现 MCP Server —— 协议分发器与工具注册中心

> 五段式教学模板：为什么学 → 是什么 → 怎么用 → 用在哪 → 避坑优化。
> 本章开始**写真代码**：把第二章的报文规则，落成一个能跑的 `McpServer` + `ToolRegistry`。
> 学完请回答章末问题：“新增一个工具，为什么不需要改 McpServer 一行代码？”

---

## 第一部分：为什么学（核心价值）

### 1. 为什么 Server 是整个 MCP 的“心脏”？

第二章我们手拼了报文，但报文得有个东西去“读懂并回应”它——这就是 Server。
Server 是 MCP 世界的**服务端中枢**：Client 发来的每一条 JSON-RPC 请求，都由 Server
解析 method、分发处理、包装响应。**没有 Server，工具就是一堆没人调的孤立函数。**

### 2. 为什么要把“协议分发”和“工具管理”拆成两个类？

这是**单一职责原则（SRP）**的直接体现：
- [`McpServer`](../../mcp/server/McpServer.java:1)：**只管协议**——读 method、分发、包响应、处理错误。它不认识任何具体工具。
- [`ToolRegistry`](../../mcp/registry/ToolRegistry.java:1)：**只管工具目录**——有哪些工具、按名字怎么找到工具。它不懂协议。

如果把两者揉在一起，会变成一个几百行、既解析协议又硬编码工具的“上帝类”，
每加一个工具就要改它，每改协议也要动它——这是灾难。拆开后，各自演化，互不干扰。

### 3. 为什么这套设计能做到“新增工具零改动”？

这是本章的**灵魂**。答案是 [`ToolRegistry`](../../mcp/registry/ToolRegistry.java:1) 的构造器注入了 `List<McpTool>`：
Spring 启动时会**自动**把容器里所有实现了 `McpTool` 接口的 Bean 收集进来。
于是“新增工具”只需要写一个新的 `@Component implements McpTool`，**Registry 和 Server 一行都不用改**。
这就是**开闭原则（OCP）**：对扩展开放（随便加工具），对修改关闭（核心代码不动）。

---

## 第二部分：是什么（架构与原理）

### 2.1 Server 侧的依赖关系

```
JsonRpcRequest（报文进来）
        │
        ▼
  ┌───────────────┐   switch(method)   ┌──────────────────┐
  │   McpServer   │ ─────────────────► │  initialize 处理  │
  │  (协议分发器)  │                    ├──────────────────┤
  │               │ ─────────────────► │  tools/list 处理  │──► ToolRegistry.listDefinitions()
  │               │                    ├──────────────────┤
  │               │ ─────────────────► │  tools/call 处理  │──► ToolRegistry.getTool(name).execute()
  └───────────────┘                    └──────────────────┘
        │
        ▼
JsonRpcResponse（报文出去）
```

McpServer 依赖两个协作者（构造器注入）：
- `ToolRegistry`：找工具、列工具。
- `McpTraceLogger`：链路埋点（收到什么、工具耗时多少、结果如何）。

### 2.2 ToolRegistry 的三大职责

翻看 [`ToolRegistry`](../../mcp/registry/ToolRegistry.java:1) 源码，它只做三件事：

1. **自动收集 + 建索引**（构造器）：注入 `List<McpTool>`，逐个放进 `LinkedHashMap<String, McpTool>`
   （name → tool）。用 LinkedHashMap 是为了**保持注册顺序**，日志更好读。
2. **重名快速失败**：如果两个工具 `name()` 相同，构造时直接抛 `IllegalStateException`。
   **宁可启动就崩，也不要上线后调用歧义**——这是企业级的防御性编程。
3. **目录导出**：`listDefinitions()` 遍历所有工具的 `definition()`，拼成 `tools/list` 要返回的清单。

### 2.3 McpServer 的分发逻辑（handle 方法）

Server 的总入口是 `handle(JsonRpcRequest)`，逻辑分三层：

**第一层：通知短路。** 如果 `request.isNotification()`（无 id），直接返回 `null` 不应答——
这正是第二章“Notification 不回复”规则的代码落地。

**第二层：method 分发（switch）。**
```
switch (method) {
    case "initialize" -> handleInitialize(request);
    case "tools/list" -> handleToolsList(request);
    case "tools/call" -> handleToolsCall(request);
    default            -> error(-32601, "方法不存在");   // METHOD_NOT_FOUND
}
```

**第三层：异常兜底。** 整个 switch 包在 try-catch 里，任何未预期异常都转成
`-32603 INTERNAL_ERROR`。**Server 绝不能因为一个请求崩掉**，这是服务端的底线。

### 2.4 tools/call 的三种结局（本章重点）

`handleToolsCall` 完美对应第二章讲的“协议错误 vs 业务失败”：

| 情况 | 返回 | 错误码/标志 |
|------|------|------------|
| 缺少 `name` 参数 | `error` | -32602 INVALID_PARAMS |
| 工具名不存在 | `error` | -32602 INVALID_PARAMS |
| 工具执行**抛异常** | `error` | -32000 SERVER_ERROR |
| 工具执行成功（含业务失败 isError:true） | `success` | 结果放 result |

注意最后一行：**哪怕工具业务失败（如除零），只要它是“正常返回了一个 isError 结果”，
就走 `success`**。只有工具“抛异常崩了”才走 `error`。这个区分是 MCP Server 的精髓。

---

## 第三部分：怎么用（源码逐段精讲）

### 3.1 ToolRegistry 构造器：OCP 的引擎

```java
public ToolRegistry(List<McpTool> tools) {
    if (tools != null) {
        for (McpTool tool : tools) {
            String name = tool.name();
            if (toolMap.containsKey(name)) {
                throw new IllegalStateException("发现重名工具：" + name);  // 快速失败
            }
            toolMap.put(name, tool);   // 建立 name → tool 索引
        }
    }
}
```
关键：`List<McpTool>` 是 Spring **自动注入**的。你写几个工具 `@Component`，这里就收到几个。

### 3.2 McpServer.handle：协议总入口

```java
public JsonRpcResponse handle(JsonRpcRequest request) {
    String method = request.getMethod();
    Object id = request.getId();
    traceLogger.logReceive(method, id);           // ① 埋点：收到请求

    if (request.isNotification()) {               // ② 通知不应答
        return null;
    }

    JsonRpcResponse response;
    try {
        response = switch (method == null ? "" : method) {   // ③ 分发
            case McpMethods.INITIALIZE -> handleInitialize(request);
            case McpMethods.TOOLS_LIST -> handleToolsList(request);
            case McpMethods.TOOLS_CALL -> handleToolsCall(request);
            default -> JsonRpcResponse.error(id,
                    JsonRpcError.of(JsonRpcError.METHOD_NOT_FOUND, "方法不存在: " + method));
        };
    } catch (Exception e) {                       // ④ 异常兜底
        response = JsonRpcResponse.error(id,
                JsonRpcError.of(JsonRpcError.INTERNAL_ERROR, "服务端内部错误: " + e.getMessage()));
    }
    traceLogger.logResponse(method, id, response != null && response.isError());
    return response;
}
```

### 3.3 handleInitialize：握手应答

返回三样东西：`protocolVersion`（"2024-11-05"）、`capabilities`（声明支持 tools）、
`serverInfo`（Server 是谁）。这正是第二章 3.1 那段 initialize result 报文的来源。

### 3.4 handleToolsCall：调用工具（计时 + 分层错误）

```java
McpTool tool = toolRegistry.getTool(toolName);
if (tool == null) {
    return JsonRpcResponse.error(id, JsonRpcError.of(INVALID_PARAMS, "工具不存在: " + toolName));
}
long start = System.currentTimeMillis();
CallToolResult toolResult;
try {
    toolResult = tool.execute(arguments);         // 执行工具
} catch (Exception e) {
    // 工具抛异常 → 协议级 SERVER_ERROR
    return JsonRpcResponse.error(id, JsonRpcError.of(SERVER_ERROR, "工具执行异常: " + e.getMessage()));
}
long cost = System.currentTimeMillis() - start;
traceLogger.logToolEnd(toolName, toolResult.isError(), cost, toolResult.asText());
return JsonRpcResponse.success(id, toolResult);   // 业务成功/失败都走 success
```

---

## 第四部分：用在哪（真实项目映射）

| 真实场景 | 对应本章哪段设计 |
|----------|-----------------|
| 企业有几十个工具，团队各自开发 | 每人写 `@Component implements McpTool`，Registry 自动收集 |
| 上线前发现两个工具重名 | Registry 构造器**启动即崩**，问题在开发期暴露 |
| 某工具 bug 抛了空指针 | Server 兜底转 SERVER_ERROR，**其他工具照常服务** |
| 调了一个不存在的方法名 | 返回 -32601，Client 明确知道“方法不对” |
| 监控每个工具的 P99 耗时 | `traceLogger.logToolEnd` 记录了 cost，可接入监控 |
| 网关统一鉴权、限流 | 都加在 Server 的 handle 入口，工具无感知 |

---

## 第五部分：避坑与优化

1. **坑：把工具硬编码进 Server 的 switch。**
   后果：每加一个工具都要改 Server，违背 OCP。
   正解：工具全交给 Registry 自收集，Server 永远只有 initialize/list/call 三个分支。

2. **坑：工具业务失败也返回协议 error。**
   后果：模型收到协议错误可能直接中断，而不是解释“查不到”。
   正解：只有工具**抛异常**才 SERVER_ERROR；业务失败走 success + isError。

3. **坑：Server 不做异常兜底。**
   后果：一个工具的 bug 让整个 Server 挂掉，所有请求受影响。
   正解：handle 外层 try-catch 兜底转 INTERNAL_ERROR。

4. **坑：重名工具静默覆盖。**
   后果：上线后调用哪个全凭注入顺序，诡异且难查。
   正解：Registry 构造器检测到重名立即抛异常，启动即失败。

5. **优化：tools/list 结果可缓存。**
   工具集在运行期一般不，`listDefinitions()` 可缓存，避免每次遍历（本项目工具少未做，企业量大可加）。

6. **优化：为工具执行加超时保护。**
   企业中工具可能是远程调用，应加超时/熔断（本项目 InProcess 不涉及，第七章展开）。

---

## 核心知识速记

- McpServer = 协议分发器，只有 initialize / tools.list / tools.call + 兜底四个分支。
- ToolRegistry = 工具目录，构造器注入 List<McpTool> 实现自动收集（OCP 引擎）。
- 通知（无 id）不应答，返回 null。
- 分层错误：缺参/工具不存在 → -32602；工具抛异常 → -32000；未预期异常 → -32603；业务失败 → success+isError。
- 重名工具启动即崩，防御性编程。

---

## 常见面试题

1. 为什么把协议分发和工具管理拆成 McpServer 和 ToolRegistry 两个类？（单一职责）
2. 新增一个工具为什么不用改 McpServer？（Registry 自动收集 List<McpTool>，OCP）
3. tools/call 遇到“工具不存在”“工具抛异常”“工具业务失败”分别返回什么？
4. 为什么 Server 的 handle 要外层 try-catch？（服务端不能被单个请求搞崩）
5. Registry 发现重名工具为什么直接抛异常而不是覆盖？（避免上线后调用歧义）

---

## 本章练习答案

> **练习：新增一个工具，为什么不需要改 McpServer 一行代码？**
>
> **参考答案：**
> 因为 MCP Server 侧采用了**依赖注入 + 开闭原则**的设计，工具的“发现”被彻底自动化了：
> 1. 所有工具都实现统一的 `McpTool` 接口，并标注 `@Component` 交给 Spring 容器管理。
> 2. [`ToolRegistry`](../../mcp/registry/ToolRegistry.java:1) 的构造器参数是 `List<McpTool>`，Spring 启动时会**自动**
>    把容器内所有 McpTool 实现注入这个 List，Registry 据此建立 name→tool 索引。
> 3. [`McpServer`](../../mcp/server/McpServer.java:1) 从不认识任何具体工具，它只在 `tools/call` 时通过
>    `toolRegistry.getTool(name)` 按名字拿工具、在 `tools/list` 时通过 `toolRegistry.listDefinitions()` 列工具。
> 4. 因此，新增工具 = 写一个新的 `@Component implements McpTool`。Spring 自动把它收进 Registry，
>    Server 通过 Registry 就能立刻发现并调用它——**Registry 和 Server 的代码完全不用改**。
>
> 这就是**开闭原则**：系统对“扩展新工具”开放，对“修改核心分发代码”关闭。
> 与 Day03 相比，Day03 的 Tool 也用了类似注册思想，但那是进程内的；MCP 把这套能力**协议化**了，
> 使得工具未来可以变成独立进程/远程服务，而 Server 的分发逻辑依然一行不改。