package com.zero.ai.agentstudy.day08multiagent.agent.core;

import com.zero.ai.agentstudy.day08multiagent.entity.AgentExecutionLog;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;

/**
 * AbstractAgent —— 所有具体 Agent 的抽象基类（模板方法模式）。
 *
 * <p>教学要点（模板方法 + SOLID·SRP/OCP）：</p>
 * <ul>
 *   <li>把「每个 Agent 都要做、但和业务无关」的横切逻辑——计时、日志、异常兜底——
 *       统一固化在 {@link #execute(AgentContext)} 这个 <b>final</b> 模板方法里；</li>
 *   <li>把「每个 Agent 各不相同」的业务逻辑留给子类的 {@link #doExecute(AgentContext)}；</li>
 *   <li>这样具体 Agent 只需专注写业务（SRP 单一职责），而横切逻辑改一处、全体生效
 *       （OCP 对扩展开放、对修改关闭）。</li>
 * </ul>
 *
 * <p>执行骨架（对应第二章的生命周期）：</p>
 * <pre>
 *   execute():
 *     记录 start / 打日志
 *     try   → doExecute()      // 子类的真正业务
 *     catch → onError()        // 统一兜底：异常绝不外抛，转成失败 AgentResult
 *     finally→ 记 costMillis、写 AgentExecutionLog 到 context
 * </pre>
 *
 * @author ZeroAi
 */
@Slf4j
public abstract class AbstractAgent implements Agent {

    /**
     * 模板方法（final，子类不可覆盖）：统一处理计时、日志、异常兜底。
     *
     * <p>这是整个框架健壮性的关键：无论子类业务里抛出什么异常，都会被这里捕获，
     * 转成 {@code AgentResult.fail(...)} 返回，从而保证「单个 Agent 挂掉不会拖垮 Coordinator」
     * （第二章避坑 3）。</p>
     *
     * @param context 协作上下文
     * @return 结构化结果（成功或失败，但绝不抛异常）
     */
    @Override
    public final AgentResult execute(AgentContext context) {
        LocalDateTime start = LocalDateTime.now();
        long startNanos = System.nanoTime();
        String inputSummary = summarizeInput(context);
        log.info("[{}] 开始执行，输入摘要：{}", role().getDisplayName(), inputSummary);

        AgentResult result;
        try {
            // === 子类的真正业务逻辑 ===
            result = doExecute(context);
            if (result == null) {
                result = AgentResult.fail(role(), "doExecute 返回了 null");
            }
        } catch (Exception e) {
            // === 统一异常兜底：异常绝不外抛 ===
            log.error("[{}] 执行异常，已兜底为失败结果：{}", role().getDisplayName(), e.getMessage(), e);
            result = onError(context, e);
        }

        long cost = (System.nanoTime() - startNanos) / 1_000_000;
        result.setCostMillis(cost);
        result.setRole(role());

        // === 写审计日志（可观测性）===
        AgentExecutionLog execLog = AgentExecutionLog.of(
                context.nextStep(),
                role(),
                result.isSuccess(),
                inputSummary,
                truncate(result.getOutput()),
                cost,
             start,
                result.getMessage()
        );
        context.addLog(execLog);
        log.info("[{}] 执行结束，成功={}，耗时={}ms", role().getDisplayName(), result.isSuccess(), cost);
        return result;
    }

    /**
     * 子类实现的核心业务逻辑（读上下文 → 产出 → 写回共享记忆 → 返回结果）。
     *
     * @param context 协作上下文
     * @return 执行结果
     * @throws Exception 允许抛出，由模板方法统一兜底
     */
    protected abstract AgentResult doExecute(AgentContext context) throws Exception;

    /**
     * 生成「输入摘要」用于日志。子类可覆盖以提供更贴切的摘要，默认返回任务主题。
     *
     * @param context 上下文
     * @return 输入摘要
     */
    protected String summarizeInput(AgentContext context) {
        return "topic=" + context.getTask().getTopic();
    }

    /**
     * 异常兜底钩子。默认转成失败结果；子类可覆盖以实现降级（如返回一个占位产出）。
     *
     * @param context 上下文
     * @param e       捕获到的异常
     * @return 失败（或降级）结果
     */
    protected AgentResult onError(AgentContext context, Exception e) {
        return AgentResult.fail(role(), role().getDisplayName() + " 执行失败：" + e.getMessage());
    }

    /** 截断长文本用于日志展示，避免日志爆炸 */
    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}