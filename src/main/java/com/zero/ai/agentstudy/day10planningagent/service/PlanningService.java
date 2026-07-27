package com.zero.ai.agentstudy.day10planningagent.service;

import com.zero.ai.agentstudy.day10planningagent.context.BudgetExceededException;
import com.zero.ai.agentstudy.day10planningagent.context.Observation;
import com.zero.ai.agentstudy.day10planningagent.context.PlanState;
import com.zero.ai.agentstudy.day10planningagent.context.PlanningContext;
import com.zero.ai.agentstudy.day10planningagent.context.TraceEvent;
import com.zero.ai.agentstudy.day10planningagent.core.Goal;
import com.zero.ai.agentstudy.day10planningagent.core.Plan;
import com.zero.ai.agentstudy.day10planningagent.core.PlanStep;
import com.zero.ai.agentstudy.day10planningagent.core.StepResult;
import com.zero.ai.agentstudy.day10planningagent.core.planner.Planner;
import com.zero.ai.agentstudy.day10planningagent.engine.Scheduler;
import com.zero.ai.agentstudy.day10planningagent.executor.StepExecutor;
import com.zero.ai.agentstudy.day10planningagent.reflection.Reflection;
import com.zero.ai.agentstudy.day10planningagent.reflection.Reflector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Planning Agent 编排器（主循环）。
 *
 * <p>串起五大能力：规划(Planner) -> 调度(Scheduler) -> 执行(StepExecutor)
 * -> 反思(Reflector) -> 重规划(Planner.replan)，全程受预算护栏(guardBudget)约束。
 *
 * <p>核心循环（Sense-Plan-Act 变体）：
 * <pre>
 *   plan = planner.plan(goal)          // 首次规划
 *   validateNoCycle(plan)              // 环检测 fail-fast
 *   while (!allDone) {
 *       guardBudgetOrThrow()           // 预算护栏
 *       step = scheduler.nextStep()    // 调度就绪步骤
 *       result = executor.execute(step)// 执行（内置重试）
 *       ctx.record(observation)        // 记录观察到黑板
 *       reflection = reflector.reflect // 反思裁决
 *       switch(verdict) {              // 四分支
 *           CONTINUE -> 继续
 *           RETRY_STEP -> 重置该步
 *           REPLAN -> 增量重规划
 *           ABORT -> 失败退出
 *       }
 *   }
 *   收尾判定 SUCCEEDED / FAILED
 * </pre>
 */
@Service
public class PlanningService {

    private static final Logger log = LoggerFactory.getLogger(PlanningService.class);

    private final Planner planner;
    private final Scheduler scheduler;
    private final StepExecutor executor;
    private final Reflector reflector;

    public PlanningService(Planner planner, Scheduler scheduler,
                           StepExecutor executor, Reflector reflector) {
        this.planner = planner;
        this.scheduler = scheduler;
        this.executor = executor;
        this.reflector = reflector;
    }

    /**
     * 运行一个规划任务，直到成功、失败或预算耗尽。
     *
     * @param goal 用户目标
     * @return 最终上下文（含计划、观察、轨迹、终态）
     */
    public PlanningContext run(Goal goal) {
        PlanningContext ctx = new PlanningContext(goal);
        ctx.trace(TraceEvent.of("START", "目标: " + goal.description()));

        try {
            // 1) 首次规划
            ctx.transitionTo(PlanState.PLANNING);
            Plan plan = planner.plan(goal);
            ctx.setPlan(plan);
            scheduler.validateNoCycle(plan);          // 环检测 fail-fast
            ctx.trace(TraceEvent.of("PLAN", plan.prettyPrint()));
            ctx.transitionTo(PlanState.READY);

            // 2) 主循环
            while (!scheduler.isAllDone(ctx.plan())) {
                ctx.guardBudgetOrThrow();

                Optional<PlanStep> next = scheduler.nextStep(ctx.plan());
                if (next.isEmpty()) {
                    // 无就绪步骤但又未全部完成：说明存在无法推进的失败步骤 -> 判失败
                    ctx.trace(TraceEvent.of("STUCK", "无可执行步骤且计划未完成"));
                    break;
                }

                PlanStep step = next.get();
                ctx.incrementStep();
                ctx.transitionTo(PlanState.EXECUTING);
                ctx.trace(TraceEvent.of("EXEC", step.id() + " -> " + step.action()));

                // 3) 执行（执行器内部已重试，绝不抛异常）
                StepResult result = executor.execute(step, ctx);
                ctx.record(new Observation(step.id(), result.success(), result.output(), result.error()));

                // 4) 反思裁决
                ctx.transitionTo(PlanState.REFLECTING);
                Reflection reflection = reflector.reflect(ctx, step, result);
                ctx.trace(TraceEvent.of("REFLECT", reflection.verdict() + " | " + reflection.reason()));

                // 5) 四分支处理
                switch (reflection.verdict()) {
                    case CONTINUE -> ctx.transitionTo(PlanState.EXECUTING);
                    case RETRY_STEP -> {
                        step.retryWith();                 // 重置为 PENDING，下轮重新调度
                        ctx.transitionTo(PlanState.EXECUTING);
                    }
                    case REPLAN -> {
                        ctx.transitionTo(PlanState.RE_PLANNING);
                        Plan replanned = planner.replan(ctx);
                        ctx.setPlan(replanned);
                        scheduler.validateNoCycle(replanned);
                        ctx.incrementReplan();
                        ctx.trace(TraceEvent.of("REPLAN", replanned.prettyPrint()));
                        ctx.transitionTo(PlanState.READY);
                    }
                    case ABORT -> {
                        ctx.trace(TraceEvent.of("ABORT", reflection.reason()));
                        return finish(ctx, false, "反思裁决中止: " + reflection.reason());
                    }
                }
            }

            // 6) 收尾判定
            boolean ok = scheduler.isAllDone(ctx.plan()) && !ctx.plan().hasFailed();
            return finish(ctx, ok, ok ? "全部步骤完成" : "存在未完成/失败步骤");

        } catch (BudgetExceededException be) {
            ctx.trace(TraceEvent.of("BUDGET", be.getMessage()));
            return finish(ctx, false, "预算耗尽: " + be.getMessage());
        } catch (Exception e) {
            log.error("规划任务异常", e);
            ctx.trace(TraceEvent.of("ERROR", e.getMessage()));
            return finish(ctx, false, "致命错误: " + e.getMessage());
        }
    }

    private PlanningContext finish(PlanningContext ctx, boolean success, String reason) {
        // 若已处于终态则不再转移（幂等）
        if (!ctx.state().isTerminal()) {
            ctx.transitionTo(success ? PlanState.SUCCEEDED : PlanState.FAILED);
        }
        ctx.trace(TraceEvent.of("END", (success ? "SUCCEEDED" : "FAILED") + " | " + reason));
        log.info("规划任务结束: {} | {}", ctx.state(), reason);
        return ctx;
    }
}