package com.zero.ai.agentstudy.day12longrunningagent.state;

import com.zero.ai.agentstudy.day12longrunningagent.lifecycle.AgentState;
import com.zero.ai.agentstudy.day12longrunningagent.session.AgentSession;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * SessionStore 的内存实现（默认，用于零依赖运行与教学演示）。
 *
 * <p>基于 {@link ConcurrentHashMap} 保证并发安全。它不具备"进程崩溃后仍存活"的持久性，
 * 但完整实现了接口语义，便于本地跑通全流程。生产环境替换为 Redis/PostgreSQL 实现即可，
 * 上层 Runtime 代码无需改动（面向接口编程的价值所在）。</p>
 */
@Component
public class InMemorySessionStore implements SessionStore {

    private final ConcurrentHashMap<String, AgentSession> store = new ConcurrentHashMap<>();

    @Override
    public void save(AgentSession session) {
        store.put(session.getSessionId(), session);
    }

    @Override
    public Optional<AgentSession> findById(String sessionId) {
        return Optional.ofNullable(store.get(sessionId));
    }

    @Override
    public List<AgentSession> findByStates(AgentState... states) {
        Set<AgentState> target = states == null || states.length == 0
                ? EnumSet.noneOf(AgentState.class)
                : EnumSet.copyOf(Arrays.asList(states));
        return store.values().stream()
                .filter(s -> target.contains(s.getState()))
                .collect(Collectors.toList());
    }

    @Override
    public List<AgentSession> findAll() {
        return List.copyOf(store.values());
    }

    @Override
    public void delete(String sessionId) {
        store.remove(sessionId);
    }

    @Override
    public long count() {
        return store.size();
    }
}