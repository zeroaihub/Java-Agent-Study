/**
 * Day17 · Code Agent（代码生成与执行）。
 *
 * <p>把 LLM 从「聊天玩具」推进到「能真正改动代码库并通过验证的工程角色」。</p>
 *
 * <p>依赖方向严格单向（越底层越稳定）：</p>
 * <pre>
 *   api  →  orchestrator  →  iterate  →  verify  →  sandbox
 *                        ↘  gitops   ↘  patch   ↘  safety
 *                        ↘  context  →  analysis →  repo
 *                        ↘  eval / observability / metering / review / testgen
 *                                    ↘  core（零依赖内核）
 * </pre>
 *
 * <p>与 Day15 / Day16 一致：业务模块零 Spring 注解，只在 {@code api/CodeAgentConfiguration}
 * 一处集中装配，保证「换掉 Sandbox 不影响 Patch」「换掉 LLM 不影响 Verify」。</p>
 *
 * @author ZeroAi
 */
package com.zero.ai.agentstudy.day17codeagent;
