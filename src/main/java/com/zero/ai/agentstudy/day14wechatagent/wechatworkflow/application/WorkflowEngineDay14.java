package com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.application;

import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model.ExecutionContext;
import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model.StepResult;
import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model.WorkflowStep;
import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model.WorkflowTask;
import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.port.Planner;
import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.port.StepExecutor;
import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.port.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 工作流编排引擎（application 层核心）。
 *
 * <p>它把 {@link Planner}（规划）、{@link StepExecutor}（执行）、{@link WorkflowRepository}（持久化）
 * 三个端口编织成一条完整的「Planning + Execution」链路，实现企业级 Agent 的多步骤自动化能力。</p>
 *
 * <p><strong>核心执行循环（顺序驱动状态机）：</strong></p>
 * <pre>
 *   plan(goal)  ->  生成 WorkflowTask（若干有序 Step）
 *        |
 *        v
 *   run(task)  ->  循环取 nextPendingStep：
 *        |            +-- 若 requiresApproval -> markWaitingApproval -> 挂起(SUSPENDED)，跳出循环
 *        |            +-- 否则 -> 路由到匹配 StepExecutor 执行
 *        |                        +-- SUCCESS -> markSucceeded + 产出写黑板
 *        |                        +-- SKIP    -> markSkipped
 *        |                        +-- FAILURE -> canRetry ? 重置重试 : markFailed 并中断
 *        |            每步后 refreshStatus + save
 *        v
 *   审批放行 approve(taskId, stepId) -> 该步 resetToPending -> 再次 run(task) 从挂起点续跑
 * </pre>
 *
 * <p>为什么用「取下一个 PENDING」而非 for 遍历索引？因为审批放行后需要<strong>从挂起点续跑</strong>，
 * 用「找第一个待执行步骤」的方式天然支持中断-恢复，无需记录游标。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
@Service
public class WorkflowEngineDay14 {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngineDay14.class);

    /** 单次 run 的最大步骤推进数，防止条件分支异常导致死循环。 */
    private static final int MAX_STEP_ITERATIONS = 100;

    private final Planner planner;
    private final List<StepExecutor> executors;
    private final WorkflowRepository repository;

    /**
     * 构造引擎（Spring 自动注入所有 StepExecutor 实现）。
     *
     * @param planner    规划器
     * @param executors  所有步骤执行器（策略集合）
     * @param repository 任务仓储
     */
    public WorkflowEngineDay14(Planner planner, List<StepExecutor> executors, WorkflowRepository repository) {
        this.planner = planner;
        this.executors = executors;
        this.repository = repository;
    }

    /**
     * 规划：把自然语言目标分解为工作流任务并持久化（状态 CREATED）。
     *
     * @param goal    自然语言目标
     * @param context 执行上下文
     * @return 已保存的工作流任务
     */
    public WorkflowTask plan(String goal, ExecutionContext context) {
        WorkflowTask task = planner.plan(goal, context);
        repository.save(task);
        log.info("[Workflow] 规划完成 taskId={} goal={} 共 {} 步", task.taskId(), goal, task.stepCount());
        return task;
    }

    /**
     * 规划并立即执行（一步到位的便捷入口）。
     *
     * @param goal    自然语言目标
     * @param context 执行上下文
     * @return 执行后（可能挂起/完成/失败）的任务
     */
    public WorkflowTask planAndRun(String goal, ExecutionContext context) {
        WorkflowTask task = plan(goal, context);
        return run(task);
    }

    /**
     * 执行工作流：从第一个 PENDING 步骤开始顺序推进，直到无待执行步骤或挂起/失败。
     *
     * @param task 工作流任务
     * @return 推进后的任务
     */
    public WorkflowTask run(WorkflowTask task) {
        task.markRunning();
   int iterations = 0;

        while (iterations++ < MAX_STEP_ITERATIONS) {
            Optional<WorkflowStep> next = task.nextPendingStep();
            if (next.isEmpty()) {
                break; // 无待执行步骤，收尾
            }
            WorkflowStep step = next.get();

            // 1) 关键步骤需人工审批且尚未放行：挂起等待放行
            if (step.isApprovalPending()) {
                step.markWaitingApproval();
                task.refreshStatus();
                repository.save(task);
                log.info("[Workflow] taskId={} 步骤[{}]需人工审批，任务挂起", task.taskId(), step.name());
                return task;
            }

            // 2) 普通步骤：路由执行
            executeStep(task, step);

            // 3) 若该步骤失败且不可重试，任务已进入终态(FAILED)，终止推进
            task.refreshStatus();
            repository.save(task);
            if (task.status().isTerminal()) {
                log.warn("[Workflow] taskId={} 任务终止，状态={}", task.taskId(), task.status());
                break;
            }
        }

        if (iterations >= MAX_STEP_ITERATIONS) {
            log.error("[Workflow] taskId={} 达到最大步骤迭代数 {}，疑似死循环，强制中断",
                    task.taskId(), MAX_STEP_ITERATIONS);
        }

        task.refreshStatus();
        repository.save(task);
        log.info("[Workflow] taskId={} 本轮执行结束，状态={}", task.taskId(), task.status());
        return task;
  }

    /**
     * 执行单个步骤：路由到匹配执行器，按结果更新步骤状态与黑板。
     *
     * @param task 所属任务
     * @param step 待执行步骤
     */
    private void executeStep(WorkflowTask task, WorkflowStep step) {
        Optional<StepExecutor> executor = findExecutor(step.action());
        if (executor.isEmpty()) {
            step.markRunning();
            step.markFailed("找不到支持 action=" + step.action() + " 的执行器");
            log.error("[Workflow] taskId={} 步骤[{}] 无匹配执行器 action={}",
                    task.taskId(), step.name(), step.action());
            return;
        }

        step.markRunning();
        log.info("[Workflow] taskId={} 执行步骤[{}] action={} 第 {} 次尝试",
                task.taskId(), step.name(), step.action(), step.attempts());

        StepResult result;
        try {
            result = executor.get().execute(step, task.context());
        } catch (Exception ex) {
            result = StepResult.failure("执行器抛出异常: " + ex.getMessage());
            log.error("[Workflow] taskId={} 步骤[{}] 执行异常", task.taskId(), step.name(), ex);
        }

        switch (result.outcome()) {
            case SUCCESS -> {
                step.markSucceeded(result.message());
                // 产出写入黑板，key 用步骤 id，供后续步骤消费
                task.context().put(step.id(), result.message());
                log.info("[Workflow] taskId={} 步骤[{}] 成功: {}", task.taskId(), step.name(), result.message());
            }
            case SKIP -> {
                step.markSkipped();
                log.info("[Workflow] taskId={} 步骤[{}] 跳过: {}", task.taskId(), step.name(), result.message());
            }
            case FAILURE -> handleFailure(task, step, result.message());
        }
    }

    /**
     * 处理步骤失败：可重试则重置为 PENDING 再试，否则标记失败。
     *
     * @param task    所属任务
     * @param step    失败步骤
     * @param message 错误信息
     */
    private void handleFailure(WorkflowTask task, WorkflowStep step, String message) {
        if (step.canRetry()) {
            step.resetToPending();
            log.warn("[Workflow] taskId={} 步骤[{}] 失败可重试(已尝试{}次/上限{}): {}",
                    task.taskId(), step.name(), step.attempts(), step.maxRetries(), message);
        } else {
            step.markFailed(message);
            log.error("[Workflow] taskId={} 步骤[{}] 重试耗尽，标记失败: {}",
                    task.taskId(), step.name(), message);
        }
    }

    /**
     * 人工审批放行：把等待审批的步骤重置为 PENDING，并从挂起点续跑。
     *
     * @param taskId 任务 ID
     * @param stepId 被审批的步骤 ID
     * @return 续跑后的任务
     */
    public WorkflowTask approve(String taskId, String stepId) {
        WorkflowTask task = repository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));
        WorkflowStep step = task.findStep(stepId)
                .orElseThrow(() -> new IllegalArgumentException("步骤不存在: " + stepId));
        step.approve();
        log.info("[Workflow] taskId={} 步骤[{}] 审批通过，恢复执行", taskId, step.name());
        return run(task);
    }

    /**
     * 人工驳回：把等待审批的步骤标记失败，任务随之进入 FAILED。
     *
     * @param taskId 任务 ID
     * @param stepId 被驳回的步骤 ID
     * @param reason 驳回原因
     * @return 更新后的任务
     */
    public WorkflowTask reject(String taskId, String stepId, String reason) {
        WorkflowTask task = repository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));
        WorkflowStep step = task.findStep(stepId)
                .orElseThrow(() -> new IllegalArgumentException("步骤不存在: " + stepId));
        step.markFailed("人工驳回: " + reason);
        task.refreshStatus();
        repository.save(task);
        log.info("[Workflow] taskId={} 步骤[{}] 被驳回: {}", taskId, step.name(), reason);
        return task;
    }

    /**
     * 按 action 查找第一个支持的执行器。
     *
     * @param action 动作类型
     * @return 执行器 Optional
     */
    private Optional<StepExecutor> findExecutor(String action) {
        return executors.stream().filter(e -> e.supports(action)).findFirst();
    }
}