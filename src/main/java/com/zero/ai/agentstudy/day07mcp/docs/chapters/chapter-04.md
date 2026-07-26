# 第四章：用 Java 实现 MCP Client —— 传输层抽象与高层封装

> 五段式教学模板：为什么学 → 是什么 → 怎么用 → 用在哪 → 避坑优化。
> 本章写 MCP 的另一半：`McpClient` + `Transport`。Server 是“接电话的”，Client 是“打电话的”。
> 学完请回章末问题：“把进程内直连换成 stdio 或 HTTP，McpClient 需要改吗？为什么？”

---

## 第一部分：为什么学（核心价值）

### 1. 为什么 Agent 不能直接调 Server，非要经过 Client？

上层的 Agent 只想说“帮我查北京天气”，它**不该关心**：
- 怎么拼 `{"jsonrpc":"2.0","id":3,"method":"tools/call",...}` 这种报文；
- id 该用几、怎么保证唯一；
- 要先 initialize 握手、再发 initialized 通知；
- 响应回来是 error 还是 success、怎么解析成强类型。

这些**协议脏活**全部由 [`McpClient`](../../mcp/client/McpClient.java:1) 封装。Agent 只面对三个干净方法：
`initialize()` / `listTools()` / `callTool(name, args)`。**Client 是协议与业务之间的翻译官。**

### 2. 为什么要有 Transport 这一层抽象？

因为工具可能在本地、也可能在远程。第二章讲过：MCP“传输无关”。
[`Transport`](../../mcp/transport/Transport.java:1) 接口就是把“**怎么把报文送出去**”抽象出来：
- 本地进程 → stdio 实现；
- 远程服务 → HTTP 实现；
- 本项目教学 → [`InProcessTransport`](../../mcp/transport/InProcessTransport.java:1) 进程内直连。

McpClient **依赖 Transport 接口**，永远不知道底层是哪种。这是**依赖倒置原则（DIP）**：
高层模块（Client）不依赖低层细节（具体传输），两者都依赖抽象（Transport 接口）。

### 3. 为什么本项目用“进程内直连”而不是真 stdio？

真 stdio 要开子进程、管理进程生命周期、把对象序列化成 JSON 写进管道、再从道读回来反序列化……
这些复杂度会**淹没协议本身**。教学阶段，我们用 InProcess：Client 把请求对象直接交给同一 JVM 里的
Server 处理。**接口和真 stdio 完全一样**，所以将来换成真跨进程，只替换 Transport 一个类，Client 不动。

---

## 第二部分：是什么（架构与原理）

### 2.1 Client 侧的分层

```
    Agent（业务）
        │  只调 initialize/listTools/callTool
        ▼
 ┌──────────────┐     依赖抽象     ┌──────────────┐
 │  McpClient   │ ───────────────► │  Transport   │（接口）
 │ (协议翻译官)  │                 └──────┬───────┘
 └──────────────┘                        │ 实现
        拼报文/解析/维护id            ┌────▼──────────────┐
                                    │ InProcessTransport │ ──► McpServer
                                    │  (可换 stdio/HTTP) │
                                    └────────────────────┘
```

### 2.2 McpClient 的四个关键机制

翻看 [`McpClient`](../../mcp/client/McpClient.java:1) 源码，它有四个精心设计的机制：

1. **id 自增生成**：用 `AtomicLong idGen`，`nextId()` 每次 `incrementAndGet()`，
   **保证并发下id 唯一**。这正是第二章“id 用于请求-响应配对”的落地。
2. **initialize 完整握手**：`initialize()` 先发 initialize 请求拿能力，**再补发一条
   `notifications/initialized` 通知**（无 id、不等响应），严格遵循 MCP 生命周期。
3. **自愈式 ensureInitialized**：`listTools()` / `callTool()` 内部先调 `ensureInitialized()`，
   若还没握手就**自动补一次 initialize**。对使用者极其友好——忘了初始化也不会报错。
4. **强类型转换**：Server 返回的 result 是通用 Map/Object，Client 用
   `objectMapper.convertValue(...)` 转成 `ToolDefinition` / `CallToolResult` 强类型，
   上层拿到的是干净的 Java 对象，不用自己解析 JSON。

### 2.3 错误处理：checkError 把协议错误变异常

```java
private void checkError(JsonRpcResponse resp, String action) {
    if (resp == null) throw new IllegalStateException(action + " 未收到响应");
    if (resp.isError()) throw new IllegalStateException(action + " 失败: " + code + msg);
}
```
**协议级错误**（如工具不存在、参数非法）在 Client 侧被转成运行时异常抛出——因为这类错误
说明“调用方式本身错了”，应该让程序员在开发期就发现。而**业务失败**（isError:true）不在这里拦，
它会随 `CallToolResult` 正常返回，交给上层 Agent/模型去解释。这与第二、三章的分层完全一致。

### 2.4 Transport 接口只有两个方法

```java
public interface Transport {
    JsonRpcResponse send(JsonRpcRequest request);  // 发请求，拿响应（通知返回 null）
    String type();                                 // 传输类型名，便于日志
}
```
极简，但极关。`send` 的语义是“我发一个 Request，同步拿回一个 Response”。
通知（无 id）返回 null。**任何传输方式，只要实现这两个方法，就能插进 MCP。**

---

## 第三部分：怎么用（源码逐段精讲）

### 3.1 initialize：握手 + 补发通知

```java
public Map<String, Object> initialize() {
    Map<String, Object> params = Map.of(
        "protocolVersion", PROTOCOL_VERSION,
        "capabilities", Map.of(),
        "clientInfo", Map.of("name", "agentstudy-mcp-client", "version", "1.0.0"));
    JsonRpcRequest req = JsonRpcRequest.of(nextId(), McpMethods.INITIALIZE, params);
    JsonRpcResponse resp = transport.send(req);
    checkError(resp, "initialize");

    transport.send(JsonRpcRequest.notification(McpMethods.INITIALIZED, null));  // 补发通知
    initialized = true;
    return (Map<String, Object>) resp.getResult();
}
```

### 3.2 listTools：发现工具并转强类型

```java
public List<ToolDefinition> listTools() {
    ensureInitialized();                                        // 自愈
    JsonRpcResponse resp = transport.send(JsonRpcRequest.of(nextId(), TOOLS_LIST, null));
    checkError(resp, "tools/list");
    Map<String,Object> result = (Map<String,Object>) resp.getResult();
    List<ToolDefinition> tools = new ArrayList<>();
    if (result.get("tools") instanceof List<?> rawList) {
        for (Object o : rawList) {
            tools.add(objectMapper.convertValue(o, ToolDefinition.class));  // Map → 强类型
        }
    }
    return tools;
}
```

### 3.3 callTool：调用工具

```java
public CallToolResult callTool(String toolName, Map<String, Object> arguments) {
    ensureInitialized();
    Map<String, Object> params = Map.of(
        "name", toolName,
        "arguments", arguments == null ? Map.of() : arguments);
    JsonRpcResponse resp = transport.send(JsonRpcRequest.of(nextId(), TOOLS_CALL, params));
    checkError(resp, "tools/call:" + toolName);
    return objectMapper.convertValue(resp.getResult(), CallToolResult.class);
}
```

### 3.4 InProcessTransport：把请求直接交给 Server

```java
@Override
public JsonRpcResponse send(JsonRpcRequest request) {
    traceLogger.logSend(TYPE, request.getMethod(), request.getId());  // 发送埋点
    // 进程内直连：直接调 Server。真 stdio 会在此把 request 序列化写进子进程 stdin，
    // 再从 stdout 读回 JSON 反序列化成 Response。
    return mcpServer.handle(request);
}
```
**这就是可替换性的证据**：这一个方法从“直接调 handle”换成“写管道/发 HTTP”，
McpClient 完全无感知，因为它只认 `Transport.send`。

---

## 第四部分：用在哪（真实项目映射）

| 真实场景 | 对应本章设计 |
|----------|-------------|
| Cursor 连接本地文件系统 MCP Server | stdio Transport 实现 |
| Agent 调用团队部署的远程数据库工具 | HTTP+SSE Transport 实现 |
| 一个 Agent 同时连多个 Server | 每个 Server 一个 Client + 一个 Transport |
| 高并发调用工具 | AtomicLong 保证 id 不冲突 |
| 上层忘了先握手就调工具 | ensureInitialized 自动补握手 |
| 工具名拼错 / 参数缺失 | checkError 抛异常，开发期即暴露 |
| 从本地工具迁到云端工具 | 只换 Transport 实现类，Client/Agent 不动 |

---

## 第五部分：避坑与优化

1. **坑：Client 直接依赖具体传输类。**
   后果：换传输方式要改 Client，违背 DIP。
   正解：Client 只依赖 `Transport` 接口（本项目正是如此）。

2. **坑：id 用固定值或非线程安全的自增。**
   后果：并发下 id 冲突，响应配错请求。
   正解：`AtomicLong.incrementAndGet()`。

3. **坑：跳过 initialized 通知。**
   后果：规范的远程 Server 认为握手没完成，拒绝后续请求。
   正解：initialize 后必须补发 `notifications/initialized`。

4. **坑：把业务失败也当异常抛。**
   后果：模型收不到“查不到城市”这种正常结果，无法向用户解释。
   正解：checkError 只拦协议 error；业务失败随 CallToolResult 正常返回。

5. **优化：异步/流式响应。**
   真实远程场景可能需要异步（CompletableFuture）或流式（SSE）。本项目同步够用，
   接口签名可平滑升级为返回 `CompletableFuture<JsonRpcResponse>`。

6. **优化：连接复用与重连。**
   远程 Transport 应管理连接池、断线重连、超时。InProcess 无此问题，企业级需补充。

---

## 核心知识速记

- McpClient = 协议翻译官，对上暴露 initialize/listTools/callTool，对下只依赖 Transport 接口。
- Transport = 传输抽象，两个方法 send/type，可插 stdio/HTTP/InProcess。
- id 用 AtomicLong 自增，保证唯一，用于请求-响应配对。
- ensureInitialized 自愈：未握手则自动补握手。
- checkError 只拦协议错误；业务失败随 CallToolResult 正常返回。
- 依赖倒置：换传输只换实现类，Client 零改动。

---

## 常见面试题

1. McpClient 为什么只依赖 Transport 接口而不是具体实现？（依赖倒置，可替换传输）
2. Client 的请求 id 为什么用 AtomicLong？（并发唯一，用于配对响应）
3. initialize 之后为什么还要发 initialized 通知？（完成 MCP 生命周期握手）
4. ensureInitialized 起什么作用？（自愈：忘了握手自动补）
5. 协议错误和业务失败在 Client 侧分别怎么处理？（前者抛异常，后者正常返回结果）

---

## 本章练习答案

> **练习：把进程内直连换成 stdio 或 HTTP，McpClient 需要改吗？为什么？**
>
> **参考答案：**
> **不需要改 McpClient 一行代码。** 原因是本项目遵循了**依赖倒置原则**：
> 1. [`McpClient`](../../mcp/client/McpClient.java:1) 的字段类型是 `Transport`（**接口**），构造器注入的也是 `Transport`。
>    它调用的永远只是 `transport.send(request)`，从不关心背后是哪种传输。
> 2. [`InProcessTransport`](../../mcp/transport/InProcessTransport.java:1) 只是 `Transport` 的一个实现，它在 `send` 里
>    “直接把请求交给同一 JVM 的 McpServer”。
> 3. 如果要换 stdio：只需新写一个 `StdioTransport implements Transport`，在它的 `send` 里把 request
>    序列化成 JSON 写入子进程 stdin、再从 stdout 读回 JSON 反序列化成 Response。
> 4. 如果要换 HTTP：新写一个 `HttpTransport implements Transport`，在 `send` 里把 request 作为
>    HTTP 请求体发出、把响应体解析成 JsonRpcResponse。
> 5. 无论哪种，只要新实现满足 `Transport` 接口（send/type 签名不变），把它注册成 Bean 注入进 Client 即可，
>    **McpClient、Agent、以及所有业务代码全部零改动**。
>
> 这正是“面向接口编程”的威力：**抽象稳定，实现可换**。报文格式（JSON-RPC）不变、Client 逻辑不变，
> 变的只是“报文怎么送出去”这一个最底层的实现细节。