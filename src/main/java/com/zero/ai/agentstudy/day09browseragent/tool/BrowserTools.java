package com.zero.ai.agentstudy.day09browseragent.tool;

import com.zero.ai.agentstudy.day09browseragent.service.BrowserActionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * BrowserTools —— 把浏览器能力暴露为 Spring AI 的 {@link Tool}，供 LLM「Tool Calling」调用。
 *
 * <p><b>这是 Day09 与前序 Day3(Tool Calling)/Day8(Multi-Agent) 的衔接点。</b>
 * LLM 本身没有「手」，只能输出文本。通过 {@code @Tool} 注解，我们把每个浏览器动作
 * 描述成一个「函数签名 + 自然语言说明」，Spring AI 会自动生成 JSON Schema 交给模型。
 * 模型在推理时若判断「需要打开网页 / 点击 / 截图」，就会返回一个 Tool 调用请求，
 * 框架据此反射调用本类方法，再把结果回填给模型——这就是 Browser Agent 的「大脑-手」闭环。</p>
 *
 * <p><b>关键工程点</b>：{@code @Tool} 的 description 和 {@code @ToolParam} 的说明
 * 直接决定 LLM 能否正确调用。描述要「面向意图」写清楚「什么时候用、参数是什么」，
 * 这是 Prompt Engineering 在 Tool 层的体现。</p>
 *
 * @author AI架构师
 */
@Slf4j
@Component
public class BrowserTools {

    private final BrowserActionService actionService;

    public BrowserTools(BrowserActionService actionService) {
        this.actionService = actionService;
    }

    @Tool(description = "打开指定网页并返回页面标题。当用户想访问某个网址、查看某个页面是否可达时使用。")
    public String openWebPage(
            @ToolParam(description = "要打开的完整网址，必须以 http://或 https:// 开头") String url) {
        log.info("[Day09][Tool] openWebPage url={}", url);
        return actionService.openPage(url);
    }

    @Tool(description = "获取指定网页的可见正文文本（已去除HTML标签）。当用户想了解页面内容、总结网页、提取信息时使用。")
    public String readPageText(
            @ToolParam(description = "目标网页完整网址") String url) {
        log.info("[Day09][Tool] readPageText url={}", url);
        String text = actionService.getText(url);
        // 控制返回长度，避免超出模型上下文
        return text.length() > 3000 ? text.substring(0, 3000) + "...(已截断)" : text;
    }

    @Tool(description = "获取指定网页渲染后的完整HTML源码。当用户需要分析页面结构、DOM、或做数据抓取时使用。")
    public String readPageHtml(
            @ToolParam(description = "目标网页完整网址") String url) {
        log.info("[Day09][Tool] readPageHtml url={}", url);
        String html = actionService.getHtml(url);
        return html.length() > 5000 ? html.substring(0, 5000) + "...(已截断)" : html;
    }

    @Tool(description = "在网页上点击某个元素。当用户想点击按钮、链接、菜单等时使用。")
    public String clickElement(
            @ToolParam(description = "网页网址") String url,
            @ToolParam(description = "要点击元素的选择器，如 '#login-btn' 或 'text=登录'") String selector) {
        log.info("[Day09][Tool] clickElement url={}, selector={}", url, selector);
        return actionService.click(url, selector);
    }

    @Tool(description = "在网页输入框中填入文本。当用户想在搜索框、表单里输入内容时使用。")
    public String typeText(
            @ToolParam(description = "网页网址") String url,
            @ToolParam(description = "输入框选择器，如 '#search' 或 'input[name=q]'") String selector,
            @ToolParam(description = "要输入的文本内容") String text) {
        log.info("[Day09][Tool] typeText url={}, selector={}", url, selector);
        return actionService.fill(url, selector, text);
    }

    @Tool(description = "登录一个网站。当用户提供账号密码并要求登录某网站时使用。")
    public String loginWebsite(
            @ToolParam(description = "登录页网址") String url,
            @ToolParam(description = "用户名输入框选择器") String userSelector,
            @ToolParam(description = "用户名") String username,
            @ToolParam(description = "密码输入框选择器") String passSelector,
            @ToolParam(description = "密码") String password,
            @ToolParam(description = "登录按钮选择器") String submitSelector) {
        log.info("[Day09][Tool] loginWebsite url={}, user={}", url, username);
        return actionService.login(url, userSelector, username, passSelector, password, submitSelector);
    }

    @Tool(description = "对指定网页全屏截图并保存，返回截图文件路径。当用户想保存页面快照、留证时使用。")
    public String captureScreenshot(
            @ToolParam(description = "目标网页网址") String url) {
        log.info("[Day09][Tool] captureScreenshot url={}", url);
        return actionService.screenshot(url);
    }

    @Tool(description = "从网页下载文件，返回本地保存路径。当用户想下载页面上的文件、报表、附件时使用。")
    public String downloadFile(
            @ToolParam(description = "网页网址") String url,
            @ToolParam(description = "触发下载的元素选择器") String triggerSelector) {
        log.info("[Day09][Tool] downloadFile url={}, selector={}", url, triggerSelector);
        return actionService.download(url, triggerSelector);
    }

    @Tool(description = "向网页上传本地文件。当用户想在页面的文件上传框里提交本地文件时使用。")
    public String uploadFile(
            @ToolParam(description = "网页网址") String url,
            @ToolParam(description = "文件上传框(input[type=file])选择器") String fileSelector,
            @ToolParam(description = "本地文件的绝对路径") String localPath) {
        log.info("[Day09][Tool] uploadFile url={}, file={}", url, localPath);
        return actionService.upload(url, fileSelector, localPath);
    }
}