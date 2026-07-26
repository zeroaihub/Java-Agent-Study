package com.zero.ai.agentstudy.day08multiagent.agent.coordinator;

import com.zero.ai.agentstudy.day08multiagent.agent.core.Agent;
import com.zero.ai.agentstudy.day08multiagent.agent.core.AgentRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * AgentManager —— Agent 注册中心 / 花名册。
 *
 * <p>教学要点（SOLID·OCP/DIP）：Coordinator 不该 new 各个 Agent，也不该关心
 * 它们的具体类型。AgentManager 利用 Spring 的「自动装配集合」特性，把容器里所有
 * {@link Agent} 实现自动收集进来，按 {@link AgentRole} 建成一张花名册。</p>
 *
 * <p>好处：</p>
 * <ul>
 *   <li><b>OCP 对扩展开放</b>：新增一个 Agent（如 TranslatorAgent），只要它是个
 *       {@code @Component} 且实现 Agent 接口，就会被自动收录，AgentManager 与
 *       Coordinator 都无需改动；</li>
 *   <li><b>DIP 依赖倒置</b>：这里依赖的是 Agent 抽象集合，而非具体类。</li>
 * </ul>
 *
 * <p>Spring 会把所有 {@code Agent} 类型的 Bean 注入到构造器的 {@code List<Agent>}。</p>
 *
 * @author ZeroAi
 */
@Slf4j
@Component
public class AgentManager {

    /** 角色 -> Agent 的花名册 */
    private final Map<AgentRole, Agent> registry = new EnumMap<>(AgentRole.class);

    /**
     * 构造时由 Spring 注入容器内所有 Agent 实现，自动建立花名册。
     *
     * @param agents 容器内所有 Agent 实现（自动装配）
     */
    public AgentManager(List<Agent> agents) {
        for (Agent agent : agents) {
            AgentRole role = agent.role();
            Agent old = registry.put(role, agent);
            if (old != null) {
                log.warn("[AgentManager] 角色 {} 存在多个实现，后者覆盖前者：{}",
                        role, agent.getClass().getSimpleName());
            }
        }
        log.info("[AgentManager] 已注册 {} 个 Agent：{}", registry.size(), registry.keySet());
    }

    /**
     * 按角色获取对应的 Agent。
     *
     * @param role 角色
     * @return 该角色的 Agent 实现
     * @throws IllegalStateException 若该角色未注册
     */
    public Agent get(AgentRole role) {
        Agent agent = registry.get(role);
        if (agent == null) {
            throw new IllegalStateException("未找到角色对应的 Agent：" + role);
        }
        return agent;
    }

    /**
     * 判断某角色是否已注册。
     *
     * @param role 角色
     * @return 是否已注册
    */
    public boolean has(AgentRole role) {
        return registry.containsKey(role);
    }
}