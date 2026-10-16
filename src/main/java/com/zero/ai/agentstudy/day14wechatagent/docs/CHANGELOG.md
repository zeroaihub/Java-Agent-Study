# 变更日志（CHANGELOG）

> 格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，
> 版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

---

## [Unreleased]

### 计划中
- 企微回调签名校验与消息解密
- 挂起态持久化
- LLM function-calling 规划替换关键词规划

---

## [0.1.0] - 2026-08-04

首个教学基线版本发布。

### Added
- 11 模块骨架：`wechat-core` / `message` / `session` / `memory` / `router` / `command` / `notify` / `workflow` / `api` / `admin` + `docs`
- 顶层编排器 `MeetingSummaryOrchestrator`：串联会话、规划、执行、审批、通知全链路
- `AgentController`：`/api/agent/instruct`、`/{taskId}/steps/{stepId}/approve`、`/reject` 三接口
- Workflow 引擎：`planAndRun` / `approve` / `reject`，支持 Human Approval 挂起-放行-续跑
- `KeywordPlanner`：关键词规则拆解步骤（教学版）
- 黑板模式 `ExecutionContext`：贯穿工作流的共享数据空间
- 通知模块 + 死信表骨架
- RAG 知识库表结构（pgvector）与检索接口骨架
- 完整教程：第一章 ~ 第十章
- 工程文档：`ARCHITECTURE` / `API` / `DATABASE` / `DEPLOY` / `TESTING`
- 项目管理文档：`TODO` / `ROADMAP` / `CHANGELOG` / `ADR`

### 架构决策
- 采用六边形架构 + DDD + Spring Modulith 模块化
- 编排（Orchestration）而非编舞（Choreography）
- 独立 `wechatagent` 模块作为顶层用例编排层
- 多租户 `tenantId` 贯穿全数据模型

### 技术栈
- Java 21 (Virtual Threads)、Spring Boot 4.1.0、Spring AI 2.0.0
- PostgreSQL 17 + pgvector、Redis 8
- Docker / Kubernetes、GitHub Actions

---

[Unreleased]: 待发布
[0.1.0]: 教学基线