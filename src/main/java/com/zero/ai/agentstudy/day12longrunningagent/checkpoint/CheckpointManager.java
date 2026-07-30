package com.zero.ai.agentstudy.day12longrunningagent.checkpoint;

import com.zero.ai.agentstudy.day12longrunningagent.session.AgentContext;
import com.zero.ai.agentstudy.day12longrunningagent.session.AgentSession;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Checkpoint 管理器：负责"打点（snapshot）"与"从检查点还原（restore）"。
 *
 * <p>打点时机由 Runtime 决定——通常在"每完成一个 Step 后"打一次点，保证崩溃时
 * 最多只丢失当前正在执行的这一步，已完成的步骤全部可续跑。</p>
 */
@Service
public class CheckpointManager {

    private final CheckpointStore checkpointStore;

    public CheckpointManager(CheckpointStore checkpointStore) {
        this.checkpointStore = checkpointStore;
    }

    /**
     * 对当前 Session 打一个检查点：把 Context 的 stepIndex / retryCount / attributes 快照下来。
     */
    public Checkpoint snapshot(AgentSession session) {
        AgentContext ctx = session.getContext();
        Checkpoint cp = new Checkpoint(
                session.getSessionId(),
                ctx.getStepIndex(),
                ctx.getRetryCount(),
                ctx.getAttributes());
        checkpointStore.append(cp);
        return cp;
    }

    /**
     * 从最近一个检查点还原 Session 的 Context（用于崩溃恢复）。
     *
     * @return 是否成功还原（无检查点时返回 false）
     */
    public boolean restore(AgentSession session) {
        Optional<Checkpoint> latest = checkpointStore.findLatest(session.getSessionId());
        if (latest.isEmpty()) {
            return false;
        }
        Checkpoint cp = latest.get();
        AgentContext ctx = session.getContext();
        ctx.setStepIndex(cp.getStepIndex());
        ctx.setRetryCount(cp.getRetryCount());
        ctx.getAttributes().clear();
        ctx.getAttributes().putAll(cp.getAttributes());
        return true;
    }
}