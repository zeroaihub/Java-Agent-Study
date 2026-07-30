package com.zero.ai.agentstudy.day12longrunningagent.recovery;

import com.zero.ai.agentstudy.day12longrunningagent.checkpoint.CheckpointManager;
import com.zero.ai.agentstudy.day12longrunningagent.lifecycle.AgentState;
import com.zero.ai.agentstudy.day12longrunningagent.session.AgentSession;
import com.zero.ai.agentstudy.day12longrunningagent.state.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

/**
 * 崩溃恢复服务（Recovery）。
 *
 * <p>进程重启后调用 {@link #recoverAll(Consumer)}：扫描 Store 中所有"活跃态"的 Session，
 * 从最近的 Checkpoint 还原其 Context，然后通过回调把它们重新交给执行体续跑。</p>
 *
 * <p>为避免与 Runtime 形成循环依赖，恢复后的"重新执行"动作以 {@link Consumer} 回调注入，
 * 由调用方（Runtime）提供具体的续跑逻辑。这是典型的依赖倒置。</p>
 */
@Service
public class RecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

    /** 需要被恢复的活跃态集合。终态（COMPLETED/FAILED/CANCELLED）不在其中。 */
    private static final AgentState[] ACTIVE_STATES = {
            AgentState.CREATED, AgentState.RUNNING,
            AgentState.RETRYING, AgentState.WAITING
            // 注意：SUSPENDED 表示"等外部事件"，不应自动续跑，故不纳入自动恢复
    };

    private final SessionStore sessionStore;
    private final CheckpointManager checkpointManager;

    public RecoveryService(SessionStore sessionStore, CheckpointManager checkpointManager) {
        this.sessionStore = sessionStore;
        this.checkpointManager = checkpointManager;
    }

    /**
     * 恢复所有活跃态 Session。
     *
     * @param resume 续跑回调：接收已还原 Context 的 Session，由调用方重新调度执行
     * @return 成功触发恢复的 Session 数量
     */
    public int recoverAll(Consumer<AgentSession> resume) {
        List<AgentSession> actives = sessionStore.findByStates(ACTIVE_STATES);
        log.info("[Recovery] 发现 {} 个活跃态 Session 需要恢复", actives.size());

        int recovered = 0;
        for (AgentSession session : actives) {
            try {
                boolean restored = checkpointManager.restore(session);
                log.info("[Recovery] sessionId={} state={} restoredFromCheckpoint={} stepIndex={}",
                        session.getSessionId(), session.getState(), restored,
                        session.getContext().getStepIndex());
                resume.accept(session);
                recovered++;
            } catch (Exception e) {
                log.error("[Recovery] 恢复 sessionId={} 失败: {}",
                        session.getSessionId(), e.getMessage(), e);
            }
        }
        log.info("[Recovery] 恢复完成，成功触发 {} 个", recovered);
        return recovered;
    }
}