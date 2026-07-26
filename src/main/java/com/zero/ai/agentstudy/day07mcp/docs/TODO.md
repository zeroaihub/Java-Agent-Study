# Day07 TODO

> 按难度分级：⭐ 必做 / ⭐⭐ 进阶 / ⭐⭐⭐ 企业挑战。
> 完成一项就在方框里打勾 `[x]`。

---

## ⭐ 必做（掌握核心，缺一不可）

- [ ] 理解 MCP 与 Function Calling、HTTP 分别解决什么问题（第一章）
- [ ] 画出 MCP 协议交互流程图：initialize → tools/list → tools/call（第二章）
- [ ] 实现 JSON-RPC 报文模型：`JsonRpcRequest / JsonRpcResponse / JsonRpcError`
- [ ] 实现 `McpTool` 接口 + `ToolRegistry` 注册表
- [ ] 实现 `McpServer`：能处理 initialize / tools.list / tools.call 并做错误处理（第三章）
- [ ] 实现 `McpClient`：connect / listTools / callTool，解析 result 与 error（第四章）
- [ ] 实现 Weather MCP Server 的三个 Tool：天气 / 时间 / 计算器（第五章）
- [ ] 完成 MCP Agent V1：自动发现 + 自动调用 + 调用链日志（第八章）

---

## ⭐⭐ 进阶（工程化，拉开差距）

- [ ] 把 Day06 的 Workflow 节点改造为“经 McpClient 调用工具”，理解解耦价值（第六章）
- [ ] 给每次 `tools/call` 增加超时与降级处理
- [ ] 给 `McpServer` 增加参数 JSON Schema 校验，非法参数返回 `-32602`
- [ ] 实现 `McpTraceLogger`：记录 traceId / tool / args / result / 耗时 的完整调用链
- [ ] 新增一个 Tool（例如“汇率查询”），验证 Agent 代码零改动即可使用
- [ ] 用 Python FastMCP 写一个等价的 Weather Server 作为对照

---

## ⭐⭐⭐ 企业挑战（贴近生产）

- [ ] 新增 `HttpSseTransport`，让 Client 通过 HTTP+SSE 连接远程 Server（跨进程）
- [ ] 支持 Host 同时持有多个 `McpClient`，聚合多个业务域 Server 的工具
- [ ] 为工具增加权限模型：按 tenant/role 做工具级白名单
- [ ] 为远程传输增加认证（API Key / OAuth2）与审计落库
- [ ] 引入 Tool schema 版本化与协议版本协商的兼容策略
- [ ] 暴露监控指标（调用量 / 错误率 / P99），接入 Micrometer
- [ ] 用 Spring AI MCP Starter 或 LangChain4j MCP 重写一版，与手写实现对照

---

## 学习检查点（自测题，答案见各章末尾）

1. MCP 和 HTTP 分别解决什么问题？（第一章）
2. `tools/list` 和 `tools/call` 的请求/响应报文结构是什么？（第二章）
3. 为什么 `McpServer` 新增工具时不需要改自身代码？依赖了什么原则？（第三章）
4. `McpClient` 为什么要依赖 `Transport` 接口而不是直接依赖 `McpServer`？（第四章）
5. 企业为什么要把工具从 Agent 进程里拆成独立 MCP Server？（第六、七章）