package com.zero.ai.agentstudy.back.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 线程池配置
 *
 * 企业级规范:
 *   1. 禁止用 Executors.newSingleThreadExecutor() 等无界队列工厂方法
 *      (每次请求创建新线程池 → 线程泄漏; 队列无界 → OOM)
 *   2. 用 ThreadPoolExecutor 显式指定核心数/最大数/队列容量/拒绝策略
 *   3. 线程要有命名, 便于排查(jstack 时能看到)
 *
 * @author ZeroAi
 */
@Configuration
public class ThreadPoolConfig {

    /**
     * 流式对话专用线程池
     * 用于异步执行 SseEmitter 的流式推送, 不阻塞 Tomcat 工作线程
     */
    @Bean("streamExecutor")
    public ExecutorService streamExecutor() {
        return new ThreadPoolExecutor(
                8,                                  // 核心线程数
                32,                                 // 最大线程数
                60L, TimeUnit.SECONDS,              // 空闲存活时间
                new LinkedBlockingQueue<>(200),     // 有界队列, 防止 OOM
                new CustomizableThreadFactory("stream-"),
                new ThreadPoolExecutor.CallerRunsPolicy()  // 队列满时由调用线程执行(背压)
        );
    }
}
