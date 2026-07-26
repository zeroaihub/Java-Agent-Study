package com.zero.ai.agentstudy.day09browseragent.core;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import lombok.Getter;

/**
 * BrowserSession —— 一次浏览器会话的「句柄」，是对 {@link BrowserContext} + {@link Page} 的封装。
 *
 * <p><b>核心概念对齐</b>：</p>
 * <ul>
 *   <li>{@link BrowserContext} = 一个完全隔离的浏览器会话（相当于一个独立的隐身窗口），
 *       拥有独立的 Cookie、localStorage、缓存。不同 Context 之间的登录态互不影响。</li>
 *   <li>{@link Page} = Context 里的一个标签页，真正承载 DOM 与用户操作。</li>
 * </ul>
 *
 * <p>为什么用「Context 隔离」而不是「每次新开 Browser」？因为 Browser 进程启动成本极高
 * （几百毫秒到数秒），而 Context 创建极快（毫秒级）。企业级做法是：
 * <b>一个 Browser 进程 + 多个 Context 并发</b>，Context 用完归还池中复用或销毁重建。</p>
 *
 * @author AI架构师
 */
public class BrowserSession implements AutoCloseable {

    /** 隔离会话（独立 Cookie/Storage） */
    @Getter
    private final BrowserContext context;

    /** 当前活动标签页 */
    @Getter
    private final Page page;

    /** 会话唯一标识，用于日志追踪与 Session 管理 */
    @Getter
    private final String sessionId;

    /** 归还池时的回调，由 Pool 注入；null 表示不归还（直接关闭） */
    private final java.util.function.Consumer<BrowserSession> releaseCallback;

    public BrowserSession(String sessionId,
                          BrowserContext context,
                          Page page,
                          java.util.function.Consumer<BrowserSession> releaseCallback) {
        this.sessionId = sessionId;
        this.context = context;
        this.page = page;
        this.releaseCallback = releaseCallback;
    }

    /**
     * 真正关闭底层资源（销毁 Context，其内所有 Page 一并关闭）。
     * 由池在销毁会话或健康检查失败时调用。
     */
    public void dispose() {
        try {
            context.close();
        } catch (Exception ignored) {
            // 关闭幂等，忽略重复关闭异常
        }
    }

    /**
     * try-with-resources 自动归还：借用方用完只需 close，
     * 由回调决定是「归还池复用」还是「真正销毁」，调用方无需关心。
     */
    @Override
    public void close() {
        if (releaseCallback != null) {
            releaseCallback.accept(this);
        } else {
            dispose();
        }
    }
}