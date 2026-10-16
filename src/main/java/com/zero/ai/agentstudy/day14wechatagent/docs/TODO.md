# 待办清单（TODO）

> 从「教学 Mock 版」走向「生产可用版」的落地清单。
> 状态：`[ ]` 待办 / `[~]` 进行中 / `[x]` 已完成（教学版）

---

## 1. 接入层（Channel）

- [x] Webhook 回调骨架（企业微信）
- [ ] 企微回调签名校验与消息解密（AES）
- [ ] 被动回复 XML / 主动应用消息 API 双通道
- [ ] 消息去重（msgId 幂等表）

## 2. 会话与记忆（Session & Memory）

- [x] 会话内存仓储 + 多轮上下文
- [ ] 会话状态落 PostgreSQL / Redis，支持水平扩缩容
- [ ] 长期记忆摘要压缩（超长会话滚动摘要）

## 3. RAG 知识库

- [x] 知识库表结构 + 检索接口骨架
- [ ] 接入 Spring AI EmbeddingModel 生成真实向量
- [ ] 文档切分（chunk）与增量入库 Pipeline
- [ ] pgvector HNSW 索引调优（ef_search）

## 4. Agent 能力

- [x] KeywordPlanner 关键词规划（教学版）
- [ ] 替换为 LLM function-calling 规划
- [ ] Office Agent 真实生成 Word/PDF 纪要
- [ ] Browser Agent 接入 Playwright 真实上传
- [ ] MCP Tool 接入真实工具服务

## 5. Workflow & 审批

- [x] planAndRun / approve / reject 挂起-续跑
- [ ] 挂起态（SUSPENDED + WAITING_APPROVAL）持久化落库
- [ ] 步骤级重试 + 超时 + Saga 补偿回滚
- [ ] 审批通知推送到审批人 + 审批链

## 6. 通知与可靠送达

- [x] 通知发送 + 死信表骨架
- [ ] 死信自动重投调度 + 告警
- [ ] 送达回执与状态回写

## 7. 可观测性

- [ ] 全链路 traceId 贯穿（MDC + OpenTelemetry）
- [ ] Micrometer 指标（任务时长、审批耗时、送达成功率）
- [ ] 结构化日志

## 8. 安全与多租户

- [x] tenantId 贯穿数据模型
- [ ] 租户级配置隔离 + 数据行级权限校验
- [ ] AI 密钥走密钥管理（KMS / Vault）

## 9. 测试与质量

- [ ] 单元测试补齐（编排器 / 领域模型）
- [ ] Testcontainers 集成测试
- [ ] ArchUnit 架构规则守护接入 CI
- [ ] 覆盖率门禁

---

> 优先级建议：**幂等去重 → 挂起态持久化 → LLM 规划 → 真实 RAG → 可观测性**。