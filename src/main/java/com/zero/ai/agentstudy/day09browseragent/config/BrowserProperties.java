package com.zero.ai.agentstudy.day09browseragent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * BrowserProperties —— Day09 Browser Agent 的统一配置项。
 *
 * <p><b>工程约束（沿用前序 Day 的隔离策略）</b>：所有配置项都带默认值，
 * 即使用户没有在 application.yml 里写 {@code day09.browser.*}，模块也能开箱即用，
 * 不会影响 Day01~Day08 的任何配置。</p>
 *
 * <p>配置前缀 {@code day09.browser}，示例：</p>
 * <pre>
 * day09:
 *   browser:
 *     headless: true
 *     browser-type: chromium
 *     pool-size: 4
 *     default-timeout-ms: 30000
 *     screenshot-dir: ./target/day09-screenshots
 *     download-dir: ./target/day09-downloads
 * </pre>
 *
 * @author AI架构师
 */
@Data
@Component
@ConfigurationProperties(prefix = "day09.browser")
public class BrowserProperties {

    /** 是否无头模式（服务器/CI 环境必须 true；本地调试可 false 看真实浏览器） */
    private boolean headless = true;

    /** 浏览器内核类型：chromium / firefox / webkit */
    private String browserType = "chromium";

    /** 浏览器池大小（BrowserContext 复用数量，决定并发能力） */
    private int poolSize = 4;

    /** 全局默认操作超时（毫秒），影响 click/fill/waitFor 等 */
    private double defaultTimeoutMs = 30_000;

    /** 页面导航超时（毫秒），navigate/goto 专用 */
    private double navigationTimeoutMs = 45_000;

    /** 截图输出目录 */
    private String screenshotDir = "./target/day09-screenshots";

    /** 下载文件输出目录 */
    private String downloadDir = "./target/day09-downloads";

    /** 统一 User-Agent（留空则用浏览器默认，可用于规避简单反爬） */
    private String userAgent = "";

    /** 视口宽度 */
    private int viewportWidth = 1280;

    /** 视口高度 */
    private int viewportHeight = 800;

    /** 是否忽略 HTTPS 证书错误（测试环境常用） */
    private boolean ignoreHttpsErrors = false;

    /** 从池中借用 Context 的最长等待时间（毫秒） */
    private long acquireTimeoutMs = 10_000;
}