package com.zero.ai.agentstudy.day12longrunningagent.checkpoint;

import java.util.List;
import java.util.Optional;

/**
 * Checkpoint 存储抽象。
 *
 * <p>为每个 Session 保存一串按时间递增的 Checkpoint 历史。恢复时取"最近一个"续跑。
 * 保留历史（而非只存最后一个）便于审计、回溯与"回滚到任意检查点"的高级能力。</p>
 */
public interface CheckpointStore {

    /** 追加一个检查点。 */
    void append(Checkpoint checkpoint);

    /** 取某个 Session 的最近一个检查点。 */
    Optional<Checkpoint> findLatest(String sessionId);

    /** 取某个 Session 的全部检查点（按创建时间升序）。 */
    List<Checkpoint> findAll(String sessionId);

    /** 删除某个 Session 的所有检查点（任务终结后可清理）。 */
    void deleteBySession(String sessionId);
}