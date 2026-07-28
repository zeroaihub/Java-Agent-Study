package com.zero.ai.agentstudy.day09browseragent.core;

import com.zero.ai.agentstudy.day09browseragent.config.BrowserProperties;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ViewportSize;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * BrowserContextPool —— 浏览器会话「资源池」，是 Day09 并发能力的核心。
 *
 * <p><b>为什么需要池？</b>如果每来一个 Agent 请求就 launch 一个新 Browser，
 * 内存和进程数会瞬间爆炸（每个 Chromium 实例约 100~300MB）。企业级方案是：
 * 用<b>信号量(Semaphore)</b>控制最大并发数，共享同一个 Browser 进程，
 * 每个请求借一个隔离的 {@link BrowserContext} 使用，用完归还。</p>
 *
 * <pre>
 *   请求A ─┐
 *   请求B ─┼─► [Semaphore(poolSize)] ─► 新建/复用 Context ─► 返回 BrowserSession
 *   请求C ─┘        (超额则排队等待)
 * </pre>
 *
 * <p>本实现采用「按需创建 + 用完销毁」的简化池模型（每次归还销毁 Context，
 * 释放信号量许可）。这样能天然避免「上一个用户的登录态污染下一个用户」的问题，
 * 满足教学与多数并发场景。若要极致性能，可改为「归还时清空 Cookie 后复用」，
 * 相关权衡在 chapter 里展开。</p>
 *
 * @author AI架构师
 */
@Slf4j
@Component
public class BrowserContextPool {

    private final PlaywrightEngine engine;
    private final BrowserProperties props;

    /** 并发许可：最多同时存在 poolSize 个活动 Context */
    private final Semaphore permits;

    /** 活动会话登记表（sessionId -> Session），用于 Session 管理与强制关闭 */
    private final ConcurrentHashMap<String, BrowserSession> activeSessions = new ConcurrentHashMap<>();

    public BrowserContextPool(PlaywrightEngine engine, BrowserProperties props) {
        this.engine = engine;
        this.props = props;
        this.permits = new Semaphore(props.getPoolSize(), true);
    }

    /**
     * 借用一个浏览器会话（阻塞式，带超时）。
     *
     * <p>用法（强烈推荐 try-with-resources 自动归还）：</p>
     * <pre>{@code
     * try (BrowserSession session = pool.acquire()) {
     *     session.getPage().navigate("https://example.com");
     * }
     * }</pre>
     *
     * @return 一个可用的浏览器会话
     */
    public BrowserSession acquire() {
        try {
            boolean got = permits.tryAcquire(props.getAcquireTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!got) {
              throw new IllegalStateException("浏览器池繁忙，获取会话超时（poolSize="
                        + props.getPoolSize() + "），请稍后重试或增大 pool-size");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("获取浏览器会话被中断", e);
        }

        try {
            BrowserSession session = createSession();
            activeSessions.put(session.getSessionId(), session);
            log.info("[Day09][Pool] 借出会话 {}，当前活动数={}", session.getSessionId(), activeSessions.size());
            return session;
        } catch (RuntimeException e) {
            // 创建失败必须归还许可，否则许可泄漏会导致池永久缩水
            permits.release();
            throw e;
        }
    }

    /**
     * 创建一个全新的隔离会话（Context + Page），并应用统一的浏览器指纹配置。
     */
    private BrowserSession createSession() {
        Browser browser = engine.browser();
        Browser.NewContextOptions options = new Browser.NewContextOptions()
                .setViewportSize(new ViewportSize(props.getViewportWidth(), props.getViewportHeight()))
                .setIgnoreHTTPSErrors(props.isIgnoreHttpsErrors());
        if (props.getUserAgent() != null && !props.getUserAgent().isBlank()) {
            options.setUserAgent(props.getUserAgent());
        }

        BrowserContext context = browser.newContext(options);
        // 统一默认超时，避免每处操作手动传超时
        context.setDefaultTimeout(props.getDefaultTimeoutMs());
        context.setDefaultNavigationTimeout(props.getNavigationTimeoutMs());

        Page page = context.newPage();
        String sessionId = "sess-" + UUID.randomUUID().toString().substring(0, 8);

        return new BrowserSession(sessionId, context, page,this::release);
    }

    /**
     * 归还会话：销毁 Context 并释放许可。由 {@link BrowserSession#close()} 自动回调。
     *
     * @param session 待归还的会话
     */
    private void release(BrowserSession session) {
        activeSessions.remove(session.getSessionId());
        session.dispose();
        permits.release();
        log.info("[Day09][Pool] 归还并销毁会话 {}，当前活动数={}", session.getSessionId(), activeSessions.size());
    }

    /**
     * 当前活动会话数（监控用）。
     *
     * @return 活动会话数量
     */
    public int activeCount() {
        return activeSessions.size();
    }

    /**
     * 应用关闭时强制清理所有残留会话，防止进程泄漏。
     */
    @PreDestroy
    public void shutdown() {
        log.info("[Day09][Pool] 关闭池，清理 {} 个残留会话", activeSessions.size());
        activeSessions.values().forEach(BrowserSession::dispose);
        activeSessions.clear();
    }
}