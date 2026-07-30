package com.zero.ai.agentstudy.day12longrunningagent.runtime;

import com.zero.ai.agentstudy.day12longrunningagent.lifecycle.AgentStateMachine;
import com.zero.ai.agentstudy.day12longrunningagent.retry.RetryPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Day12 Long Running Agent 模块的装配配置。
 *
 * <p>本模块大量使用 {@code @Component/@Service} 自动装配，但有两类 Bean 需要在此
 * 显式声明——它们是普通 POJO（不带 Spring 注解），却被其他组件依赖注入：</p>
 * <ul>
 *   <li>{@link AgentStateMachine}：无状态、线程安全的状态机，被 {@code AgentRuntime} 依赖。</li>
 *   <li>{@link RetryPolicy}：重试策略，被 {@code TaskDispatcher} 依赖，用默认参数注册。</li>
 * </ul>
 *
 * <p>同时开启 {@link EnableScheduling}——这是 {@code AgentScheduler} 的
 * {@code @Scheduled} 心跳生效的前提。没有它，调度器的定时驱动方法永远不会被调用，
 * 整台机器就"心跳停摆"了。</p>
 *
 * <p>为什么把这些集中在一个配置类，而不是给 POJO 直接打注解？因为
 * {@link RetryPolicy} 与 {@link AgentStateMachine} 是<b>可复用的纯领域对象</b>，
 * 不该与 Spring 强耦合——它们在纯 Java 单元测试里也要能 {@code new} 出来直接用。
 * 把"如何装配"的决策留给配置类，是依赖注入的最佳实践。</p>
 */
@Configuration
@EnableScheduling
public class Day12RuntimeConfig {

    /**
     * 注册状态机为单例 Bean。无状态、线程安全，全局共享一个实例即可。
     */
    @Bean
    public AgentStateMachine agentStateMachine() {
        return new AgentStateMachine();
    }

    /**
     * 注册默认重试策略：最多 3 次，基础 1s，上限 30s，抖动 1s。
     *
     * <p>生产环境可通过 {@code @ConfigurationProperties} 从配置文件读取参数，
     * 这里用默认值保证零配置可跑通。</p>
     */
    @Bean
    public RetryPolicy retryPolicy() {
        return RetryPolicy.defaultPolicy();
    }
}