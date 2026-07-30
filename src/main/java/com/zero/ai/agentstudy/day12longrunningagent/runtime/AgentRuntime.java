package com.zero.ai.agentstudy.day12longrunningagent.runtime;

import com.zero.ai.agentstudy.day12longrunningagent.checkpoint.CheckpointManager;
import com.zero.ai.agentstudy.day12longrunningagent.event.AgentEvent;
import com.zero.ai.agentstudy.day12longrunningagent.event.EventBus;
import com.zero.ai.agentstudy.day12longrunningagent.lifecycle.AgentState;
import com.zero.ai.agentstudy.day12longrunningagent.lifecycle.AgentStateMachine;
import com.zero.ai.agentstudy.day12longrunningagent.session.AgentSession;
import com.zero.ai.agentstudy.day12longrunningagent.state.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Agent 运行时总控（Runtime）—— 全书能力的"总装车间"。
 *
 * <p>前面每一章都造好了一个零件：状态机、会话存储、检查点、恢复、事件总线……
 * 本类把它们组装成一台可运转的机器，并对外提供长任务的<b>统一操作入口</b>：
 * 创建会话、状态流转、打检查点、崩溃恢复。</p>
 *
 * <h3>核心不变式：状态变更的"一致动作序列"</h3>
 * <p>本 Runtime 最重要的职责，是保证<b>每一次状态变更都走同一套动作序列</b>，
 * 绝不允许业务代码绕过：</p>
 * <pre>
 *   1) transit(from, to)   —— 经状态机校验合法性，非法立即抛异常
 *   2) session.setState(to)—— 校验通过才真正写入内存态
 *   3) store.save(session) —— 立刻落库（状态外置，崩溃可恢复）
 *   4) checkpoint.snapshot —— 视需要打点（保存进度指针）
 *   5) eventBus.publish    —— 广播状态变更事件（驱动监控/下游）
 * </pre>
 * <p>把这五步收敛进一个方法 {@link#transitionTo}，任何调用方都无法"只改内存不落库"
 * 或"改了状态却不发事件"——一致性由 Runtime 强制保证，这正是"总控"存在的意义。</p>
 */
@Service
public class AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);

    private final AgentStateMachine stateMachine;
    private final SessionStore sessionStore;
    private final CheckpointManager checkpointManager;
    private final EventBus eventBus;

    public AgentRuntime(AgentStateMachine stateMachine,
                        SessionStore sessionStore,
                        CheckpointManager checkpointManager,
                        EventBus eventBus) {
        this.stateMachine = stateMachine;
        this.sessionStore = sessionStore;
        this.checkpointManager = checkpointManager;
        this.eventBus = eventBus;
    }

    // ---------------- 会话创建 ----------------

    /**
     * 创建一个新会话并落库。初始状态 CREATED，并广播 SESSION_CREATED 事件。
     *
     * @param agentType Agent 类型（决定用哪套 Step 定义），如 "github-trending"
     * @return 已持久化的新会话
     */
    public AgentSession createSession(String agentType) {
        AgentSession session = new AgentSession(agentType);
        sessionStore.save(session);
        eventBus.publish(AgentEvent.of("SESSION_CREATED", session.getSessionId(), agentType));
        log.info("[Runtime] 创建会话 sessionId={}, type={}", session.getSessionId(), agentType);
      return session;
    }

    // ---------------- 核心：一致的状态流转 ----------------

    /**
     * 把会话流转到目标状态——全系统<b>唯一</b>合法的状态变更入口。
     *
     * <p>执行"校验 → 写内存 → 落库 → 广播"的一致动作序列，任一步语义清晰、缺一不可。</p>
     *
     * @param session   目标会话
     * @param to        目标状态
     * @param eventType 变更后要广播的事件类型（供监控/下游订阅）
     * @throws com.zero.ai.agentstudy.day12longrunningagent.lifecycle.IllegalStateTransitionException 非法流转
     */
    public void transitionTo(AgentSession session, AgentState to, String eventType) {
        AgentState from = session.getState();
        // 1) 校验：非法流转在此抛出，绝不写入
        stateMachine.transit(from, to);
        // 2) 写内存态
        session.setState(to);
        // 3) 状态外置：立刻落库
        sessionStore.save(session);
        // 4) 广播事件（驱动监控指标、下游联动）
        eventBus.publish(AgentEvent.of(eventType, session.getSessionId(), to.name()));
        log.info("[Runtime] 状态流转 sessionId={} {} -> {}", session.getSessionId(), from, to);
    }

    // ---------------- 打点：完成一步后保存进度 ----------------

    /**
     * 会话推进到下一步并打检查点。
     *
     * <p>典型调用时机：一个 Step 成功执行完毕后。先 advance 进度指针，
     * 再 snapshot 落点，最后广播 CHECKPOINT_SAVED——这样崩溃时最多只丢当前这一步。</p>
     *
     * @return 打点后的 stepIndex
     */
    public int advanceAndCheckpoint(AgentSession session) {
        int step = session.getContext().advance();
        sessionStore.save(session);
        checkpointManager.snapshot(session);
        session.heartbeat();
        eventBus.publish(AgentEvent.of("CHECKPOINT_SAVED", session.getSessionId(), step));
        log.info("[Runtime] 打点 sessionId={} stepIndex={}", session.getSessionId(), step);
        return step;
    }

    // ---------------- 常用状态流转的语义化封装 ----------------

    /** 启动：CREATED/WAITING/RETRYING/SUSPENDED -> RUNNING。 */
    public void start(AgentSession session) {
        transitionTo(session, AgentState.RUNNING, "SESSION_STARTED");
    }

    /** 挂起：RUNNING -> SUSPENDED（等待外部事件唤醒）。 */
    public void suspend(AgentSession session) {
        transitionTo(session, AgentState.SUSPENDED, "SESSION_SUSPENDED");
    }

    /** 唤醒：SUSPENDED -> RUNNING。 */
    public void resume(AgentSession session) {
        transitionTo(session, AgentState.RUNNING, "SESSION_RESUMED");
    }

    /** 本轮完成、等待下次触发：RUNNING -> WAITING（周期任务）。 */
    public void waitNext(AgentSession session) {
        transitionTo(session, AgentState.WAITING, "SESSION_WAITING");
    }

    /** 进入重试等待：RUNNING -> RETRYING。 */
    public void retrying(AgentSession session, String reason) {
        session.setLastError(reason);
        transitionTo(session, AgentState.RETRYING, "TASK_RETRIED");
    }

    /** 成功完成：-> COMPLETED（终态）。 */
    public void complete(AgentSession session) {
        transitionTo(session, AgentState.COMPLETED, "SESSION_COMPLETED");
    }

    /** 失败结束：-> FAILED（终态）。 */
    public void fail(AgentSession session, String reason) {
        session.setLastError(reason);
        transitionTo(session, AgentState.FAILED, "SESSION_FAILED");
    }

    /** 主动取消：-> CANCELLED（终态）。 */
    public void cancel(AgentSession session) {
        transitionTo(session, AgentState.CANCELLED, "SESSION_CANCELLED");
    }

    // ---------------- 查询 ----------------

    public Optional<AgentSession> find(String sessionId) {
        return sessionStore.findById(sessionId);
    }

    public long sessionCount() {
        return sessionStore.count();
    }
}