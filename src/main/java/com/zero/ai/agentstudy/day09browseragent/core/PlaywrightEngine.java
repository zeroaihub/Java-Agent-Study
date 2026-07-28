package com.zero.ai.agentstudy.day09browseragent.core;

import com.zero.ai.agentstudy.day09browseragent.config.BrowserProperties;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PlaywrightEngine —— Playwright 生命周期的「唯一持有者」。
 *
 * <p><b>为什么要单独抽一层？</b>Playwright 的对象层次是：
 * <pre>
 *   Playwright(进程级驱动)
 *      └── Browser(浏览器进程)
 *             └── BrowserContext(独立会话/隐身窗口)
 *                    └── Page(标签页)
 * </pre>
 * 其中 {@link Playwright} 会启动一个 Node 驱动子进程，{@link Browser} 会启动真正的
 * 浏览器进程。二者<b>创建昂贵、必须复用、且必须显式关闭</b>，否则会造成进程泄漏。
 * 因此把它们做成 Spring 单例 Bean，随应用启动/关闭统一管理生命周期。</p>
 *
 * <p><b>线程安全</b>：Playwright 官方规定「Browser 可跨线程共享，但同一个 Page
 * 不能被多线程同时操作」。所以本类只负责产出 Browser，真正的并发隔离交给
 * {@code BrowserContextPool} 用 Context 来做。</p>
 *
 * @author AI架构师
 */
@Slf4j
@Component
public class PlaywrightEngine {

    private final BrowserProperties props;

    /** 进程级驱动，整个应用只有一个 */
    private Playwright playwright;

    /** 浏览器进程，整个应用只有一个，供所有 Context 复用 */
    private Browser browser;

    public PlaywrightEngine(BrowserProperties props) {
        this.props = props;
    }

    /**
     * 应用启动时初始化。放在 {@link PostConstruct} 而非懒加载，
     * 是为了让「浏览器内核缺失 / 驱动启动失败」这类问题在启动阶段就暴露，
     * 而不是等第一个请求进来才炸——这是企业级「快速失败(fail-fast)」原则。
     */
    @PostConstruct
    public void init() {
        log.info("[Day09][PlaywrightEngine] 正在启动 Playwright，浏览器类型={}, headless={}",
                props.getBrowserType(), props.isHeadless());
        this.playwright = Playwright.create();

        BrowserType browserType = resolveBrowserType();
        BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                .setHeadless(props.isHeadless())
                // 服务器环境常见坑：容器里没有 /dev/shm，加这些参数避免 Chromium 崩溃
                .setArgs(java.util.List.of("--no-sandbox", "--disable-dev-shm-usage"));

        this.browser = browserType.launch(launchOptions);
        log.info("[Day09][PlaywrightEngine] 浏览器已启动: {}", browser.version());
    }

    /**
     * 根据配置解析浏览器内核。
     *
     * @return 对应的 BrowserType
     */
    private BrowserType resolveBrowserType() {
        return switch (props.getBrowserType().toLowerCase()) {
            case "firefox" -> playwright.firefox();
            case "webkit" -> playwright.webkit();
            default -> playwright.chromium();
        };
    }

    /**
     * 获取共享的 Browser 实例。
     *
     * @return 浏览器实例
     */
    public Browser browser() {
        if (browser == null) {
            throw new IllegalStateException("Browser 尚未初始化，请检查 PlaywrightEngine 启动是否成功");
        }
        return browser;
    }

    /**
     * 应用关闭时释放资源。<b>顺序很重要</b>：先关 Browser（浏览器进程），
     * 再关 Playwright（驱动子进程），反了可能导致资源无法回收。
     */
    @PreDestroy
    public void destroy() {
        log.info("[Day09][PlaywrightEngine] 正在释放 Playwright 资源...");
        try {
            if (browser != null) {
                browser.close();
            }
        } catch (Exception e) {
            log.warn("[Day09][PlaywrightEngine] 关闭 Browser 异常: {}", e.getMessage());
        }
        try {
            if (playwright != null) {
                playwright.close();
            }
        } catch (Exception e) {
            log.warn("[Day09][PlaywrightEngine] 关闭 Playwright 异常: {}", e.getMessage());
        }
        log.info("[Day09][PlaywrightEngine] 资源释放完成");
    }
}