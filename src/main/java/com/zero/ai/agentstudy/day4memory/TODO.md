# Day4 动手任务清单：Memory Agent

> 按难度分级：⭐ 必做（打基础）｜⭐⭐ 进阶（练工程）｜⭐⭐⭐ 企业挑战（拔高）。
> 建议顺序完成，每完成一项在 `[ ]` 中打勾。

---

## ⭐ 必做（跑通八章核心入口）

- [ ] **启动服务**：确认本地大模型（OpenAI 兼容接口）就绪，启动 Spring Boot，端口 8080。
- [ ] **无 Memory 对比**：POST `/api/day4/chapter1/no-memory`，连续发两条消息，确认第二次不记得第一次内容。
- [ ] **有 Memory 对比**：POST `/api/day4/chapter1/with-memory`（sessionId=s1），连续发两条消息，确认第二次能回忆第一次。
- [ ] **Session 隔离**：用 sessionId=s2 提问，确认读不到 s1 的记忆。
- [ ] **Memory 分类**：GET `/api/day4/chapter2/map`，查看四大分类及存储建议。
- [ ] **自动归类**：POST `/api/day4/chapter2/classify`，传入一段用户描述，观察归入哪类 Memory。
- [ ] **成本增长**：GET `/api/day4/chapter3/growth-report`，观察 Token 随轮次线性增长。
- [ ] **滑动窗口**：POST `/api/day4/chapter3/message-window`，发超过 10 轮，确认旧消息被裁剪。
- [ ] **UserProfile CRUD**：POST `/api/day4/chapter4/profile` 创建画像，GET 查询确认。
- [ ] **字段决策**：POST `/api/day4/chapter4/decide`，传入敏感信息（如 token），确认拒绝入库。
- [ ] **框架抽象**：GET `/api/day4/chapter5/design`，理解 ChatMemory + conversationId 设计。
- [ ] **完整 Agent**：POST `/api/day4/chapter6/chat`，发送含姓名/职业/目标的消息，再追问"你记住了什么"。
- [ ] **企业实践**：GET `/api/day4/chapter7/lifecycle`，查看各类 Memory 的生命周期策略。
- [ ] **收官作品**：POST `/api/day4/chapter8/chat`，完成完整对话 → 追问 → 验证画像更新。

---

## ⭐⭐ 进阶（工程化打磨）

- [ ] **读懂数据流**：对照 chapter8 代码，理解「读记忆 → 组装 Prompt → 调 LLM → 存记忆 → 更新画像」完整链路。
- [ ] **窗口大小实验**：修改 chapter3 的 maxTurns 参数（如改为 3），观察裁剪行为变化。
- [ ] **摘要压缩触发**：在 chapter3 中持续对话直到触发摘要压缩，观察 promptPreview 变化。
- [ ] **兴趣抽取规则**：修改 chapter6 的 `ProfileSignalExtractor`，增加新的关键词匹配规则。
- [ ] **置信度过滤**：在 chapter4 中创建 confidence=0.5 的画像，验证 `isUsable()` 返回 false。
- [ ] **跨 Session 隔离测试**：同一 userId 不同 sessionId，确认短期记忆隔离但长期画像共享。
- [ ] **Prompt 结构优化**：修改 PromptBuilder，调整画像/摘要/最近对话的注入顺序，观察回答差异。

---

## ⭐⭐⭐ 企业挑战（生产级演进）

- [ ] **真实 Redis**：将 chapter6 的内存 Map 替换为 Spring Data Redis（List + TTL + LTRIM）。
- [ ] **真实 MySQL**：将 UserProfile 存储替换为 JPA/MyBatis，支持持久化和并发安全。
- [ ] **LLM 自动抽取画像**：用 Spring AI 结构化输出，让 LLM 从对话中自动提取 UserProfile 字段。
- [ ] **摘要压缩**：旧对话超 10 轮时调 LLM 生成摘要，替代简单丢弃。
- [ ] **Memory + RAG 协同**：在 PromptBuilder 中预留 ragContext 插槽，接入 Day5 向量检索结果。
- [ ] **可观测**：为 Memory 读写增加 traceId 日志 + Micrometer 指标（命中率/延迟）。
- [ ] **隐私合规**：实现用户画像查看/编辑/删除 API，敏感字段脱敏，操作审计日志。
- [ ] **并发安全**：模拟多请求同时更新同一用户画像，验证乐观锁/合并策略生效。

---

## 自检清单（提交前对照）

- [ ] 八章入口均能正常返回，无 500 错误。
- [ ] 有/无 Memory 对比效果明显：无记忆时第二次不记得，有记忆时能回忆。
- [ ] Session 隔离正确：不同 sessionId 互不干扰。
- [ ] 滑动窗口生效：超过 N 轮后旧消息被裁剪，promptMessages 不超限。
- [ ] UserProfile 支持创建/查询/更新，敏感信息被拒绝入库。
- [ ] 最终 Agent 能根据长期画像调整回答风格（如 Java 优先）。
- [ ] 理解 Memory 本质：不是模型记住，而是应用层注入 Prompt。
- [ ] 理解 Memory 与 RAG 的边界：Memory 存用户信息，RAG 存业务知识。
