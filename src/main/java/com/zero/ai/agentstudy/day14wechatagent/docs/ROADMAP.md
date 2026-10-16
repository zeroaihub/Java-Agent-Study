# 版本路线图（ROADMAP）

> 项目：Day14 Enterprise AI WeChat Agent
> 从教学基线到生产集成的演进规划。

---

## v0.1.0 — 教学基线（Current）

**主题：把能力讲透、把架构立住**

- 11 模块骨架：wechat-core / message / session / memory / router / command / notify/ workflow / api / admin + docs
- 顶层编排器 MeetingSummaryOrchestrator + AgentController
- 会议纪要全链路（Mock 实现）：Planning → 执行 → Human Approval → 通知
- 完整教程（10 章）+ 工程文档（架构/API/数据库/部署/测试）

## v0.2.0 — 真实接入

**主题：能连上企业微信真实收发消息**

- 企微回调签名校验 + AES 消息解密
- 被动回复 + 主动应用消息双通道
- msgId 幂等去重
- 会话状态持久化到 PostgreSQL / Redis

## v0.3.0 — 智能升级

**主题：从关键词规则到真正的 LLM 规划**

- KeywordPlanner → LLM function-calling 规划
- Spring AI EmbeddingModel + pgvector 真实 RAG 召回
- 文档切分入库 Pipeline
- Office Agent 真实生成纪要文档

## v0.4.0 — 生产健壮性

**主题：可靠、可观测、可运维**

- 挂起态持久化 + 崩溃恢复
- 步骤重试 / 超时 / Saga 补偿
- 死信自动重投 + 告警
- 全链路 traceId + Micrometer 指标 + 结构化日志

## v0.5.0 — 平台化集成

**主题：并入 ZeroHub AI Agent Platform**

- 多租户配置隔离 + 行级权限
- 统一鉴权 / 网关接入
- Browser Agent（Playwright）+ MCP Tool 真实工具生态
- 审批链 + 审批人推送

## v1.0.0 — 正式发布

**主题：生产可用、文档齐备、测试达标**

- 覆盖率门禁达标（领域层 ≥85%）
- ArchUnit 架构守护接入 CI
- 完整 CI/CD（GitHub Actions）+ K8s 部署
- SLA / 容量规划 / 灰度发布方案

---

> 里程碑顺序可依业务优先级调整，但 **幂等 → 持久化 → 可观测** 是生产化不可跳过的地基。