/**
 * Day15 · Enterprise AI Knowledge Platform（企业级 AI 知识平台）
 *
 * <p>本包实现一个可投入企业生产、支持多租户、可商业化 SaaS 的企业级 AI 知识平台，
 * 最终可无缝集成到 ZeroHub AI Agent Platform，供 Office / WeChat / Customer Service /
 * Search 等 Agent 统一调用。</p>
 *
 * <p>架构原则：DDD + Clean Architecture + Hexagonal + Spring Modulith，
 * 依赖方向严格单向（越底层越稳定）：</p>
 *
 * <pre>
 * api / admin
 *   → workspace / acl / sync / browser
 *     → retriever / reranker
 *       → embedding
 *         → chunk
 *           → parser
 *             → document
 *               → core
 * </pre>
 *
 * <p>14 个子模块：core、document、parser、chunk、embedding、retriever、
 * reranker、workspace、acl、sync、browser、api、admin，以及 docs 文档。</p>
 *
 * <p>详见 {@code docs/README.md} 与 {@code docs/ARCHITECTURE.md}。</p>
 *
 * @author ZeroAi
 */
package com.zero.ai.agentstudy.day15knowledgeplatform;