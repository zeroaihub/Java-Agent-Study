# 第二章：MCP 底层原理 —— JSON-RPC、报文模型与生命周期

> 五段式教学模板：为什么学 → 是什么 → 怎么用 → 用在哪 → 避坑优化。
> 本章解决**原理**问题：MCP 到底用什么协议、报文长什么样、一次会话怎么走完。
> 学完请回答章末问题：“一次 MCP 会话从握手到调用工具，经历了哪几步？每一步的报文 id 有什么区别？”

---

## 第一部分：为什么学（核心价值）

### 1. 为什么必须先懂协议，再写代码？

第一章我们建立了认知：MCP 是“AI 工具世界的 USB-C”。但认知不能落地成代码。
你如果直接去看 Spring AI 的 MCP starter，会看到一堆 `McpClient`、`McpSchema`、`Transport`，
**完全不知道它们在干什么**。原因是：这些类都是对底层协议报文的封装。
**不懂协议报文，就永远只能“抄配置”，出了问题一行都改不动。**

我们这一 Day 的核心理念是：**手写一遍协议，把黑盒变白盒。** 只有当你亲手拼出
`{"jsonrpc":"2.0","id":1,"method":"tools/list"}` 这样的报文，你才真正理解 MCP。

### 2. 为什么 MCP 选择 JSON-RPC 而不是 REST？

| 维度 | REST（HTTP 风格） | JSON-RPC（MCP 选它） |
|------|-------------------|----------------------|
| 语义 | 面向**资源**（URL 即资源） | 面向**方法调用**（远程调函数） |
| 契合度 | “调用一个工具”硬套成资源很别扭 | “调用工具”天然就是 method call |
| 传输 | 绑定 HTTP 动词（GET/POST…） | **传输无关**，可跑在 stdio / HTTP / WebSocket |
| 双向 | 天生请求-响应，反向通知麻烦 | 内建 Notification，支持 Server→Client 反向消息 |
| 报文 | 每家 API 结构各异 | 结构统一（jsonrpc/id/method/params） |

一句话：**MCP 的本质是“远程调用工具”，JSON-RPC 就是为“远程方法调用”而生的，语义天然契合，且不绑定传输层。**

### 3. 为什么“传输无关”这么重要？

因为工具可能在**任何地方**：
- 本地进程（你电脑上的文件系统工具）→ 用 **stdio**（标准输入输出管道）。
- 远程服务（团队部署的数据库工具）→ 用 **HTTP + SSE**（服务端推送事件）。
- 本项目教学 → 用 **InProcess**（进程内直连，最简单，专注协议本身）。

JSON-RPC 只规定“报文长什么样”，不规定“报文怎么送过去”。这就是**传输层抽象**的价值：
换传输方式，报文一个字都不用改。这正是本项目 [`Transport`](../../mcp/transport/Transport.java:1) 接口存在的理由。

---

## 第二部分：是什么（概念 + 底层原理）

### 2.1 JSON-RPC 2.0 报文模型（四种报文）

MCP 的所有通信，本质上就是在传递四种 JSON-RPC 报文：

```
① Request（请求，有 id，要回复）
{ "jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {...} }

② Response - 成功（有 id，对应某个 Request）
{ "jsonrpc": "2.0", "id": 1, "result": {...} }

③ Response - 失败（有 id，携带 error）
{ "jsonrpc": "2.0", "id": 1, "error": { "code": -32602, "message": "Invalid params" } }

④ Notification（通知，无 id，不需要回复）
{ "jsonrpc": "2.0", "method": "notifications/initialized" }
```

**核心判定规则（务必记住）：**
- **有 `id`** → 是 Request，对方**必须**返回一个带**相同 id** 的 Response。
- **无 `id`** → 是 Notification，是“单向广播”，对方**不回复**。
- Response 里 **`result` 和 `error` 二选一**，绝不同时出现。
- `id` 的作用是**把 Response 和 Request 配对**（异步场景可能乱序返回）。

这套规则在本项目里对应两个类：
- [`JsonRpcRequest`](../../mcp/protocol/JsonRpcRequest.java:1)：`of(id, method, params)` 造请求，`notification(method, params)` 造通知，`isNotification()` 判断。
- [`JsonRpcResponse`](../../mcp/protocol/JsonRpcResponse.java:1)：`success(id, result)` / `error(id, error)` / `isError()`。

### 2.2 错误码体系（JSON-RPC 标准 + MCP 扩展）

错误码不是随便写的，JSON-RPC 规定了保留区间：

| 错误码 | 常量名 | 含义 | 什么时候用 |
|--------|--------|------|-----------|
| -32700 | PARSE_ERROR | JSON 解析失败 | 收到的根本不是合法 JSON |
| -32600 | INVALID_REQUEST | 不是合法的 Request | 缺 jsonrpc 字段等 |
| -32601 | METHOD_NOT_FOUND | 方法不存在 | 调了没实现的 method |
| -32602 | INVALID_PARAMS | 参数非法 | 工具名不存在、必填参数缺失 |
| -32603 | INTERNAL_ERROR | 服务端内部错误 | 未预期异常兜底 |
| -32000 | SERVER_ERROR | 服务端自定义错误 | 工具执行抛异常 |

对应本项目 [`JsonRpcError`](../../mcp/protocol/JsonRpcError.java:1) 的常量：`METHOD_NOT_FOUND=-32601`、`INVALID_PARAMS=-32602`、`INTERNAL_ERROR=-32603`、`SERVER_ERROR=-32000`。

> **关键区分（第七章会再强调）：协议级错误 ≠ 工具业务失败。**
> - 协议级错误：走 `JsonRpcResponse.error`，比如“工具不存在”“参数缺失”“服务崩了”。
> - 工具业务失败：走 `JsonRpcResponse.success`，但 `result` 里 `CallToolResult.isError=true`。
>   比如“除数为 0”“查不到这个城市”——这是**正常的业务结果**，要交给模型判断，不是协议错误。

### 2.3 MCP 生命周期（一次完整会话的四个阶段）

这是本章最重要的图。一次 MCP 会话严格按顺序走：

```
Client                                         Server
  │                                              │
  │  ① initialize (Request, id=1)                │  能力协商
  │  ─────────────────────────────────────────► │  “我支持哪些能力？你支持哪些？”
  │  ◄──────────────────────────────────────── │
  │     initialize result (协议版本+能力)         │
  │                                              │
  │  ② notifications/initialized (Notification)  │  握手确认
  │  ─────────────────────────────────────────► │  “我准备好了”（无 id，不回复）
  │                                              │
  │  ③ tools/list (Request, id=2)                │  能力发现
  │  ─────────────────────────────────────────► │  “你有哪些工具？”
  │  ◄───────────────────────────────────────── │
  │     tools 列表（每个含 name/desc/inputSchema） │
  │                                              │
  │  ④ tools/call (Request, id=3)                │  工具调用
  │  ─────────────────────────────────────────► │  “帮我执行 get_weather(city=北京)”
  │  ◄───────────────────────────────────────── │
  │     CallToolResult（content isError）      │
  │                                              │
```

四个阶段对应的方法常量，在本项目 [`McpMethods`](../../mcp/protocol/McpMethods.java:1) 中定义：
`INITIALIZE = "initialize"`、`INITIALIZED = "notifications/initialized"`、`TOOLS_LIST = "tools/list"`、`TOOLS_CALL = "tools/call"`。

**为什么要有 initialize 握手？** 因为双方要**协商协议版本和能力**。
好比两个人打电话，先确认“咱俩说的是同一种语言、都能听懂对方”，才开始正事。
MCP 当前协议版本是 `"2024-11-05"`（Anthropic 2024 年 11 月发布）。

### 2.4 inputSchema：工具的“说明书”

`tools/list` 返回的每个工具，都带一个 `inputSchema`（JSON Schema 格式），
它告诉模型“这个工具怎么调、要传什么参数”：

```json
{
  "name": "get_weather",
  "description": "查询指定城市的天气",
  "inputSchema": {
    "type": "object",
    "properties": {
      "city": { "type": "string", "description": "城市名，如 北京" }
    },
    "required": ["city"]
  }
}
```

对应本项目 [`ToolDefinition`](../../mcp/entity/ToolDefinition.java:1)：`of(name, description, properties, required)`，
其中 inputSchema 字段用 `@JsonProperty("inputSchema")` 映射，确保序列化出来符合协议命名。

**inputSchema 是模型的“眼睛”**：模型不看你的 Java 代码，它只看 inputSchema 来决定
“该不该调这个工具、该传什么参数”。所以 description 写得好不好，直接决定模型用得对不对。

---

## 第三部分：怎么用（手拼报文实战）

本章不写 Java 类（那是第三、四章的事），但我们要**手工拼出**一次完整会话的所有报文，
让你对着协议一个字段一个字段地看清楚。假设我们有 `get_weather` 工具。

### 3.1 第一步：initialize（能力协商）

**Client 发出：**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2024-11-05",
    "capabilities": {},
    "clientInfo": { "name": "agentstudy-mcp-client", "version": "1.0.0" }
  }
}
```

**Server 回复：**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": "2024-11-05",
    "capabilities": { "tools": {} },
    "serverInfo": { "name": "agentstudy-mcp-server", "version": "1.0.0" }
  }
}
```
注意：请求 id=1，回复 id 也必须=1。`capabilities.tools` 表示“我支持工具能力”。

### 3.2 第二步：notifications/initialized（握手确认，无 id）

**Client 发出（Notification，无 id，Server 不回复）：**
```json
{ "jsonrpc": "2.0", "method": "notifications/initialized" }
```
这一步是“确认握手完成”。因为它是通知，所以**没有 id、没有回复**。

### 3.3 第三步：tools/list（发现工具）

**Client 发出：**
```json
{ "jsonrpc": "2.0", "id": 2, "method": "tools/list" }
```

**Server 回复：**
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "tools": [
      {
        "name": "get_weather",
        "description": "查询指定城市的天气",
        "inputSchema": {
          "type": "object",
          "properties": { "city": { "type": "string", "description": "城市名" } },
          "required": ["city"]
        }
      }
    ]
  }
}
```

### 3.4 第四步：tools/call（调用工具）

**Client 发出：**
```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "get_weather",
    "arguments": { "city": "北京" }
  }
}
```

**Server 回复（业务成功）：**
```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [ { "type": "text", "text": "北京：晴，25℃" } ],
    "isError": false
  }
}
```

**Server 回复（业务失败——注意仍是 success，不是 error！）：**
```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [ { "type": "text", "text": "查不到城市：火星" } ],
    "isError": true
  }
}
```

**Server 回复（协议错误——工具名根本不存在，这才用 error）：**
```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "error": { "code": -32602, "message": "工具不存在: get_stock" }
}
```

对着这三种回复反复看，你就彻底理解了“业务失败 vs 协议错误”的区别。
这正是第三章 [`McpServer`](../../mcp/server/McpServer.java:1) `handleToolsCall` 的核心分支逻辑。

---

## 第四部分：用在哪（真实项目中的协议应用）

| 场景 | 用到哪个报文/机制 |
|------|------------------|
| Cursor 启动时列出所有 MCP 工具 | `initialize` → `tools/list` |
| 用户让 AI“查一下这个文件” | `tools/call`（name=read_file） |
| 长任务进度推送（如大文件索引） | Server → Client 的 Notification（反向通知） |
| Server 反过来请模型帮忙生成内容 | Sampling 原语（本 Day 不实现，但协议支持） |
| 远程工具服务（跨机器） | 同样的报文，换成 HTTP+SSE 传输 |
| 工具参数校验失败 | `error` 返回 `-32602 INVALID_PARAMS` |
| 工具执行抛异常 | `error` 返回 `-32000 SERVER_ERROR` |
| 工具正常返回“没找到” | `success` + `isError:true`，交模型判断 |

**核心洞察**：无论工具多复杂、部署在哪，**报文格式永远是这四种**。
这就是协议的力量——**用有限的规则，覆盖无限的场景**。

---

## 第五部分：避坑与优化（协议层高频坑）

1. **坑：把“业务失败”当“协议错误”返回。**
   现象：查不到城市时返回 `error: -32000`。后果：模型收到协议错误，可能直接中断，
   而不是把“查不到”当成一个正常结果去解释给用户。
   正解：业务失败一律走 `success` + `isError:true`。

2. **坑：Notification 硬要回复。**
   现象：给 `notifications/initialized` 返回了一个�� id 的 Response。
   后果：对方收到一个“凭空冒出来、id 对不上”的 Response，轻则忽略，重则报错。
   正解：无 id 的报文绝不回复。本项目 [`McpServer`](../../mcp/server/McpServer.java:1) 里对 Notification 返回 `null`。

3. **坑：Response 的 id 和 Request 对不上。**
   现象：异步/并发场景下，用了错误的 id，导致回复配不到请求。
   正解：id 必须原样透传。本项目用 `AtomicLong` 递增生成 id，见 [`McpClient`](../../mcp/client/McpClient.java:1)。

4. **坑：跳过 initialize 直接 tools/call。**
   现象：没握手就调用，规范的 Server 会拒绝。
   正解：严格按生命周期走。本项目 [`McpClient`](../../mcp/client/McpClient.java:1) 用 `ensureInitialized()` 自愈——
   调用前若未初始化，自动补一次 initialize。

5. **优化：inputSchema 的 description 要写给“模型”看，不是给人看。**
   模型靠 description 判断该不该调工具。写“城市名，如 北京/上海”比只写“城市”效果好得多。

---

## 核心知识速记

- MCP = JSON-RPC 2.0 + 一套约定的方法名（initialize / tools.list / tools.call …）。
- 四种报文：Request（有 id 要回）、Success Response、Error Response、Notification（无 id 不回）。
- id 的唯一作用：把 Response 配对回 Request。
- 生命周期：initialize（协商）→ initialized（握手，通知）→ tools/list（发现）→ tools/call（调用）。
- 错误分层：协议错误走 error（-32700~-32000）；业务失败走 success + isError:true。
- 传输无关：报文格式固定，stdio/HTTP/InProcess 可自由替换。

---

## 常见面试题

1. MCP 为什么用 JSON-RPC 而不是 REST？（语义契合方法调用 + 传输无关 + 内建通知）
2. Request 和 Notification 在报文上怎么区分？（有无 id）
3. JSON-RPC 里 result 和 error 能同时出现吗？（不能，二选一）
4. “除数为 0”应该返回协议 error 还是 success？为什么？（success + isError:true，这是业务失败不是协议错误）
5. 一次完整 MCP 会话有哪几个阶段？（initialize → initialized → tools/list → tools/call）
6. initialize 握手的目的是什么？（协商协议版本与能力）
7. 错误码 -32601 和 -32602 分别代表什么？（方法不存在 / 参数非法）

---

## 本章练习答案

> **练习：一次 MCP 会话从握手到调用工具，经历了哪几步？每一步的报文 id 有什么区别？**
>
> **参考答案：**
> 一次完整会话经历 **四步**：
> 1. **initialize**（Request，**有 id**，如 id=1）：Client 发起能力协商，声明自己支持的协议版本和能力；
>    Server 返回**相同 id** 的 Response，告知自己的协议版本、能力和 serverInfo。这一步是“握手协商”。
> 2. **notifications/initialized**（Notification，**无 id**）：Client 通知 Server“我准备好了，握手完成”。
>    因为是通知，所以**没有 id，Server 不回复**。
> 3. **tools/list**（Request，**有 id**，如 id=2）：Client 请求工具清单；Server 返回相同 id 的 Response，
>    携带每个工具的 name / description / inputSchema。这一步是“能力发现”。
> 4. **tools/call**（Request，**有 id**，如 id=3）：Client 请求执行某个工具，携带 name 和 arguments；
>    Server 返回相同 id 的 Response，成功时是 CallToolResult（content + isError），
>    协议出错时是 error（携带错误码）。这一步是“工具调用”。
>
> **id 的区别规律：**
> - **有 id 的报文（步骤 1、3、4）都是 Request**，Server 必须返回**携带相同 id** 的 Response，用于配对。
> - **无 id 的报文（步骤 2）是 Notification**，是单向通知，Server **不返回任何东西**。
> - id 的本质作用是：在可能乱序、并发的通信中，**把每个响应准确地对应回它的请求**。