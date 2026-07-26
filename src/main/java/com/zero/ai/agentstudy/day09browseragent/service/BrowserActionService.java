package com.zero.ai.agentstudy.day09browseragent.service;

import com.example.agentstudy.day09browseragent.config.BrowserProperties;
import com.example.agentstudy.day09browseragent.core.BrowserContextPool;
import com.example.agentstudy.day09browseragent.core.BrowserSession;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * BrowserActionService —— 浏览器「原子操作」的统一门面（Facade）。
 *
 * <p><b>设计定位</b>：本类是 Browser Agent 的「能力层」，把 Playwright 的底层 API
 * 封装成一组语义清晰、可被 Spring AI Tool 直接调用的高层动作。上层无论是 REST
 * Controller、Workflow 节点，还是 LLM 的 Tool Calling，都只面向本类，不直接碰
 * Playwright 原生对象——这保证了「能力复用」与「替换底层引擎」的可能性。</p>
 *
 * <p>每个方法都遵循「借会话 → 操作 → try-with-resources 自动归还」的资源安全范式。</p>
 *
 * @author AI架构师
 */
@Slf4j
@Service
public class BrowserActionService {

    private final BrowserContextPool pool;
    private final BrowserProperties props;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    public BrowserActionService(BrowserContextPool pool, BrowserProperties props) {
        this.pool = pool;
        this.props = props;
    }

    /**
     * 打开网页并返回标题。这是最基础的「导航」动作。
     *
     * @param url 目标地址
     * @return 页面标题
     */
    public String openPage(String url) {
        try (BrowserSession session = pool.acquire()) {
            Page page = session.getPage();
            page.navigate(url);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            String title = page.title();
            log.info("[Day09][Action] 打开页面成功 url={}, title={}", url, title);
            return title;
        }
    }

    /**
     * 获取指定 URL 的纯文本内容（去标签后的可读正文）。
     *
     * @param url 目标地址
     * @return 页面可见文本
     */
    public String getText(String url) {
        try (BrowserSession session = pool.acquire()) {
            Page page = session.getPage();
            page.navigate(url);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            // innerText 取渲染后的可见文本，比 HTML 更适合喂给 LLM
            return page.innerText("body");
        }
    }

    /**
     * 获取指定 URL 的完整 HTML 源码（渲染后的 DOM 快照）。
     *
     * @param url 目标地址
     * @return HTML 字符串
     */
    public String getHtml(String url) {
        try (BrowserSession session = pool.acquire()) {
            Page page = session.getPage();
            page.navigate(url);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            // content() 返回的是 JS 执行后的 DOM，而非原始响应，能拿到动态渲染内容
            return page.content();
        }
    }

    /**
     * 在页面上对某个选择器执行点击。
     *
     * @param url      页面地址
     * @param selector CSS/文本选择器，如 "#submit" 或 "text=登录"
     * @return 点击后的页面标题
     */
    public String click(String url, String selector) {
        try (BrowserSession session = pool.acquire()) {
            Page page = session.getPage();
            page.navigate(url);
            // Playwright 的 click 内置「自动等待元素可交互」，无需手动 sleep
            page.click(selector);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            return page.title();
        }
    }

    /**
     * 在输入框填入文本。
     *
     * @param url      页面地址
     * @param selector 输入框选择器
     * @param text     要输入的内容
     * @return 输入后该元素的 value
     */
    public String fill(String url, String selector, String text) {
        try (BrowserSession session = pool.acquire()) {
            Page page = session.getPage();
            page.navigate(url);
            page.fill(selector, text);
            return page.inputValue(selector);
        }
    }

    /**
     * 通用登录动作：填账号、填密码、点登录，返回登录后页面文本片段。
     *
     * <p>这是「多步操作组合」的典型示例——真实登录往往还需处理验证码、跳转、
     * 二次确认，这里给出可扩展的骨架。</p>
     *
     * @param url            登录页地址
     * @param userSelector   用户名输入框选择器
     * @param username       用户名
     * @param passSelector   密码输入框选择器
     * @param password       密码
     * @param submitSelector 登录按钮选择器
     * @return 登录后页面文本前 500 字
     */
    public String login(String url, String userSelector, String username,
                        String passSelector, String password, String submitSelector) {
        try (BrowserSession session = pool.acquire()) {
            Page page = session.getPage();
            page.navigate(url);
            page.fill(userSelector, username);
            page.fill(passSelector, password);
            page.click(submitSelector);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            String body = page.innerText("body");
            log.info("[Day09][Action] 登录流程执行完成 url={}, user={}", url, username);
            return body.length() > 500 ? body.substring(0, 500) : body;
        }
    }

    /**
     * 对页面全屏截图并保存到配置目录。
     *
     * @param url 页面地址
     * @return 截图文件的绝对路径
     */
    public String screenshot(String url) {
        try (BrowserSession session = pool.acquire()) {
            Page page = session.getPage();
            page.navigate(url);
            page.waitForLoadState(LoadState.NETWORKIDLE);

            Path dir = ensureDir(props.getScreenshotDir());
            Path file = dir.resolve("shot_" + LocalDateTime.now().format(TS) + ".png");
            page.screenshot(new Page.ScreenshotOptions().setPath(file).setFullPage(true));
            log.info("[Day09][Action] 截图已保存 {}", file.toAbsolutePath());
            return file.toAbsolutePath().toString();
        }
    }

    /**
     * 显式等待某个选择器出现（用于处理异步渲染 / 动态加载）。
     *
     * @param url      页面地址
     * @param selector 要等待的选择器
     * @param timeout  超时毫秒
    * @return 是否在超时内出现
     */
    public boolean waitForSelector(String url, String selector, double timeout) {
        try (BrowserSession session = pool.acquire()) {
            Page page = session.getPage();
            page.navigate(url);
            try {
                page.waitForSelector(selector,
                        new Page.WaitForSelectorOptions()
                                .setState(WaitForSelectorState.VISIBLE)
                                .setTimeout(timeout));
                return true;
            } catch (Exception e) {
                log.warn("[Day09][Action] 等待选择器超时 selector={}", selector);
                return false;
            }
        }
    }

    /**
     * 触发页面某按钮的下载，并保存文件到配置目录。
     *
     * @param url            页面地址
     * @param triggerSelector 触发下载的元素选择器
     * @return 下载文件的绝对路径
     */
    public String download(String url, String triggerSelector) {
        try (BrowserSession session = pool.acquire()) {
            Page page = session.getPage();
            page.navigate(url);
            // waitForDownload 会在 Runnable 内触发点击，并捕获下载事件——这是 Playwright 的事件驱动范式
            Download download = page.waitForDownload(() -> page.click(triggerSelector));
            Path dir = ensureDir(props.getDownloadDir());
            Path target = dir.resolve(download.suggestedFilename());
            download.saveAs(target);
            log.info("[Day09][Action] 文件下载完成 {}", target.toAbsolutePath());
            return target.toAbsolutePath().toString();
        }
    }

    /**
     * 向 file input 上传本地文件。
     *
     * @param url          页面地址
     * @param fileSelector file 类型 input 的选择器
     * @param localPath    本地文件路径
     * @return 操作结果描述
     */
    public String upload(String url, String fileSelector, String localPath) {
        try (BrowserSession session = pool.acquire()) {
            Page page = session.getPage();
            page.navigate(url);
            page.setInputFiles(fileSelector, Paths.get(localPath));
            log.info("[Day09][Action] 文件上传完成 file={}", localPath);
            return "已上传: " + localPath;
        }
    }

    /**
     * 确保目录存在。
     */
    private Path ensureDir(String dir) {
        Path path = Paths.get(dir);
        try {
            Files.createDirectories(path);
        } catch (Exception e) {
            throw new IllegalStateException("创建目录失败: " + dir, e);
        }
        return path;
    }
}