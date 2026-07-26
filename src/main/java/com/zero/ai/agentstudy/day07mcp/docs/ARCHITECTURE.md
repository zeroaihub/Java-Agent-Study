# Day07 架构文档：MCP Agent 平台

> 本文描述 Day07 `day07mcp` 模块的整体架构、Client/Server 结构、Tool 调用流程、协议交互流程、Java 模块关系与未来扩展方向。

---

## 1. 整体架构图

```
+-----------------------------------------------------------------+
|                      Host / Agent 平台 (Spring Boot)             |
|                                                                 |
|   Controller  ->  Service(编排)  ->  McpClient(发现/调用工具)     |
|                                              |                  |
+----------------------------------------------|------------------+
                                               | JSON-RPC
                                        +------v-------+
                                        |  McpServer   |
                                        |  (进程内)    |
                                        |  dispatch    |
                                        +------+-------+
                                               |
                                    +----------v-----------+
                                    |     ToolRegistry     |
                                    +----------+-----------+
                                               |
                        +----------------------+----------------------+
                        |                      |                      |
                  +-----v-----+          +-----v-----+          +-----v-----+
                  | WeatherTool|         |  TimeTool |          | CalcTool  |
                  +-----------+          +-----------+          +-----------+
```

Day07 为教学目的，Client 与 Server 通过 **进程内 Transport（InProcessTransport）** 直连，
但接口设计与真实 stdio / HTTP 传输完全一致，未来替换传输实现即可跨进程/跨网络。

---

## 2. MCP Client 架构

```
McpClient
  ├── connect()        # 发送 initialize，完成能力协商与握手
  ├── listTools()      # 发送 tools/list，缓存工具清单
  ├── callTool(name,args)  # 发送 tools/call，解析 result/error
  └── transport        # 依赖的传输通道（面向接口，不关心底层）
```

职责：**只负责“说协议”**，不关心工具怎么实现。对上层 Service 暴露语义化方法（listTools/callTool），
对下层依赖 `Transport` 接口收发 JSON-RPC 报文。

---

## 3. MCP Server 架构

```
McpServer
  ├── handle(JsonRpcRequest) -> JsonRpcResponse   # 统一入口，按 method 分发
  ├── initialize()   # 返回协议版本与 capabilities
  ├── tools/list     # 从 ToolRegistry 收集所有 Tool 的 schema
  ├── tools/call     # 校验参数 -> 找到 Tool -> execute -> 包装结果/错误
  └── ToolRegistry   # 工具注册与查找
```

职责：**只负责“解析协议 + 分发到工具”**，工具的业务逻辑放在各个 `Tool` 实现里。
新增工具时，Server 代码零改动（开闭原则）。

---

## 4. Tool 调用流程

```
用户请求
  -> Controller 接收
  -> Service 决定调用哪个工具(或由 Agent/LLM 决策)
  -> McpClient.callTool("get_weather", {city:"北京"})
       -> 构造 JsonRpcRequest(method=tools/call)
       -> Transport.send()
            -> McpServer.handle()
                 -> 校验 name/arguments
                 -> ToolRegistry.find("get_weather")
                 -> WeatherTool.execute(args)
                 -> 包装为 CallToolResult
       <- JsonRpcResponse(result)
  <- 解析 result 返回给上层
  -> Controller 返回给用户
全过程记录调用链日志：traceId / tool / args / result / 耗时
```

---

## 5. 协议交互流程（生命周期）

```
Client                                  Server
  | initialize(protocolVersion, caps) --->|
  |<-- result(serverInfo, capabilities) --|
  | notifications/initialized ----------->|   握手完成
  | tools/list -------------------------->|
  |<-- result(tools[])--------------------|   发现工具
  | tools/call(name,args) --------------->|
  |<-- result(content) 或 error ----------|   调用工具
  | (会话结束/关闭) ---------------------->|
```

错误统一走 JSON-RPC error 对象：`{code, message, data}`，例如：
- `-32601` Method not found（未知 method）
- `-32602` Invalid params（参数非法）
- `-32000` Server error（工具执行异常）

---

## 6. Java 模块关系

```
day07mcp
├── controller
│    └── McpAgentController         依赖 -> service
├── service
│    └── McpAgentService            依赖 -> mcp.client.McpClient
├── mcp
│    ├── protocol
│    │    ├── JsonRpcRequest
│    │    ├── JsonRpcResponse
│    │    └── JsonRpcError
│    ├── transport
│    │    ├── Transport (接口)
│    │    └── InProcessTransport    直连 McpServer
│    ├── tool
│    │    ├── McpTool (接口)
│    │    ├── WeatherTool
│    │    ├── TimeTool
│    │    └── CalculatorTool
│    ├── registry
│    │    └── ToolRegistry          持有 List<McpTool>
│    ├── server
│    │    └── McpServer             依赖 -> registry
│    └── client
│         └── McpClient             依赖 -> transport
├── workflow
│    └── (Day06 节点升级为经 McpClient 调用)
├── config
│    └── McpConfig                  装配 registry/server/transport/client
├── entity / dto
└── util
     └── McpTraceLogger             调用链日志
```

依赖方向严格单向：`controller -> service -> client -> transport -> server -> registry -> tool`，
上层不反向依赖下层实现，符合 SOLID 的依赖倒置与单一职责。

---

## 7. 未来扩展方向

1. **传输层扩展**：新增 `StdioTransport`（连接外部进程）与 `HttpSseTransport`（连接远程 Server），
   `McpClient` 无需改动，只替换注入的 `Transport` 实现。
2. **多 Server 聚合**：Host 持有多个 `McpClient`，聚合多个业务域 Server 的工具。
3. **接入真实 LLM**：把 Day02 的 LLM 调用接进 `McpAgentService`，由模型决定调用哪个工具（真正的 Agent）。
4. **对接官方 SDK**：用 Spring AI MCP Starter / LangChain4j MCP 替换手写实现，对照理解。
5. **企业治理**：在 Server 前置权限、限流、审计、监控（第七章内容工程化）。
6. **Resource / Prompt / Sampling**：补齐另外三个原语，支持资源读取与提示词复用。
```