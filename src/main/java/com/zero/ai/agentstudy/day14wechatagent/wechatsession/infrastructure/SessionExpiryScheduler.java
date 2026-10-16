package com.zero.ai.agentstudy.day14wechatagent.wechatsession.infrastructure;

import com.zero.ai.agentstudy.day14wechatagent.wechatsession.application.SessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 会话过期定时扫描器（仅内存实现时启用）。
 * <p>
 * 内存 {@link InMemorySessionRepository} 没有 TTL 能力，需要外部定时任务
 * 主动扫描并清理过期会话；因此仅当容器中存在内存仓储 Bean 时才装配本组件
 * （{@link ConditionalOnBean}）。若切换为 Redis 实现，其自带 TTL 会自动
 * 淘汰过期键，本扫描器不再需要。
 *
 * <p>需在启动类上开启 {@code @EnableScheduling}。
 *
 * @author ZeroAi
 */
@Slf4j
@Component
@ConditionalOnBean(InMemorySessionRepository.class)
public class SessionExpiryScheduler {

    private final SessionService sessionService;
    private final InMemorySessionRepository repository;

    public SessionExpiryScheduler(SessionService sessionService,
                                  InMemorySessionRepository repository) {
        this.sessionService = sessionService;
        this.repository = repository;
    }

    /**
     * 每 60 秒扫描一次会话快照，清理过期会话。
     * <p>
     * 遍历的是不可变快照，扫描期间的新增会话待下一轮处理，避免并发修改。
     */
    @Scheduled(fixedDelayString = "${wechat.session.sweep-interval-ms:60000}")
    public void sweep() {
        int total = repository.size();
        if (total == 0) {
            return;
        }
        int expired = sessionService.sweepExpired(repository.snapshot());
        log.debug("[Session] 过期扫描：总数={}，本轮清理={}", total, expired);
    }
}