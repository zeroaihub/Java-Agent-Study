/**
 * Day16 · Enterprise Knowledge Agent V2（企业级知识智能体 V2）
 *
 * <p>本包在 Day15「Enterprise AI Knowledge Platform」之上，将「知识平台」升级为
 * 真正能够被多个企业 Agent 统一调用的 <b>Enterprise Knowledge Agent V2</b>，
 * 并最终集成到 ZeroHub AI Agent Platform。</p>
 *
 * <p><b>Day16 不重复教学 Day5 的 RAG 基础</b>（Embedding / Chunk / Vector / Retriever），
 * 而是聚焦于让 Agent「会思考、会规划、可信任、可治理」的企业级能力：</p>
 *
 * <pre>
 * 理解问题 → 判断知识 → 选知识库 → 选检索策略 → 多路检索 → Rerank →
 * 权限过滤 → 知识验证 → 生成答案 → 引用溯源 → 依据校验 →
 * 发现知识缺口 → 自动重检索 → 必要时人工 → 输出可信答案
 * </pre>
 *
 * <p><b>核心能力（20 项）：</b>Knowledge Routing、Multi Knowledge Base、Query Rewrite、
 * Multi Query Retrieval、Hybrid Retrieval、Reranking、Permission-aware Retrieval、
 * Citation、Answer Grounding、Knowledge Verification、Knowledge Confidence、Feedback、
 * Knowledge Gap、Knowledge Update、Knowledge Version、Evaluation、Observability、
 * Cache、Memory 联动、Agent Router。</p>
 *
 * <p><b>架构原则：</b>DDD + Clean Architecture + Hexagonal + Spring Modulith，
 * 依赖方向严格单向（越底层越稳定）：</p>
 *
 * <pre>
 * api / admin
 *   → router                       (Agent Router / Knowledge Router)
 *     → query                      (Query Rewrite / Decomposition / Multi-Query)
 *       → retrieval                (Hybrid: Vector + Keyword/BM25 + Metadata)
 *         → reranker               (召回/排序分离)
 *           → acl                  (Permission-aware Filter)
 *             → citation / verifier(Evidence / Grounding / Verification)
 *               → memory / feedback / evaluation / observability / tenant
 *                 → core           (零框架依赖的领域内核)
 * </pre>
 *
 * <p><b>技术栈：</b>Java 21 LTS、Spring Boot 4.1.0、Spring AI 2.0.0、Maven、
 * PostgreSQL 17 + pgvector、Redis 8、Docker / Compose / Kubernetes、
 * Micrometer + OpenTelemetry、JUnit 5 + Testcontainers、GitHub Actions、Playwright、MCP。</p>
 *
 * <p><b>工程隔离：</b>本包独立于 Day1~Day15，不修改任何此前课程代码；
 * 复用训练营统一的 {@code agent-study} 工程与统一规范，代码位于独立包
 * {@code com.zero.ai.agentstudy.day16knowledgeagentv2}。</p>
 *
 * <p>详见 {@code docs/README.md}、{@code docs/ARCHITECTURE.md} 与 {@code docs/chapters/}。</p>
 *
 * @author ZeroAi
 */
package com.zero.ai.agentstudy.day16knowledgeagentv2;