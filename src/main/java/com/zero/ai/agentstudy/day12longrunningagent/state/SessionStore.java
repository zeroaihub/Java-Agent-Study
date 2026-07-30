package com.zero.ai.agentstudy.day12longrunningagent.state;

import com.zero.ai.agentstudy.day12longrunningagent.lifecycle.AgentState;
import com.zero.ai.agentstudy.day12longrunningagent.session.AgentSession;

import java.util.List;
import java.util.Optional;

/**
 * Session 状态存储抽象（State Persistence）。
 *
 * <p>这是"状态外置"的核心接口：Runtime 不在内存里保存权威状态，而是把每一次状态变更
 * 落到 Store。进程崩溃后，权威状态仍在 Store 中，Recovery 可据此恢复。</p>
 *
 * <p>本课程提供内存实现 {@link InMemorySessionStore}（便于零依赖运行与测试）。
 * 生产环境可替换为 Redis（热数据）+ PostgreSQL（冷/持久数据）的分层实现，接口不变。</p>
 */
public interface SessionStore {

    /** 保存或更新一个 Session（幂等 upsert）。 */
    void save(AgentSession session);

    /** 按 ID 查询。 */
    Optional<AgentSession> findById(String sessionId);

    /** 查询所有处于指定状态集合的 Session（Recovery 扫描活跃态时使用）。 */
    List<AgentSession> findByStates(AgentState... states);

    /** 查询全部 Session。 */
    List<AgentSession> findAll();

    /** 删除一个 Session。 */
    void delete(String sessionId);

    /** 当前存储的 Session 总数。 */
    long count();
}