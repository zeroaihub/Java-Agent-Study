package com.zero.ai.agentstudy.day09browseragent.controller;

import com.example.agentstudy.day09browseragent.common.R;
import com.example.agentstudy.day09browseragent.dto.AgentTaskRequest;
import com.example.agentstudy.day09browseragent.service.BrowserActionService;
import com.example.agentstudy.day09browseragent.service.BrowserAgentService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * BrowserController —— Day09 Browser Agent 的 REST 入口。
 *
 * <p>提供两类接口：</p>
 * <ol>
 *   <li><b>原子操作接口</b>（/action/*）：直接调用某个浏览器动作，便于单元验证与工作流编排。</li>
 *   <li><b>Agent 接口</b>（/agent/run）：接受自然语言，由 LLM 自主决定调用哪些浏览器工具。</li>
 * </ol>
 *
 * <p>路径前缀 {@code /day09/browser}，与前序 Day 的 Controller 路径隔离。</p>
 *
 * @author AI架构师
 */
@Slf4j
@RestController
@RequestMapping("/day09/browser")
public class BrowserController {

    private final BrowserActionService actionService;
    private final BrowserAgentService agentService;

    public BrowserController(BrowserActionService actionService,
                             BrowserAgentService agentService) {
        this.actionService = actionService;
        this.agentService = agentService;
    }

    /** 打开网页并返回标题。 */
    @GetMapping("/action/open")
    public R<String> open(@RequestParam String url) {
        return R.ok(actionService.openPage(url));
    }

    /** 获取网页可见文本。 */
    @GetMapping("/action/text")
    public R<String> text(@RequestParam String url) {
        return R.ok(actionService.getText(url));
    }

    /** 获取网页 HTML 源码。 */
    @GetMapping("/action/html")
    public R<String> html(@RequestParam String url) {
        return R.ok(actionService.getHtml(url));
    }

    /** 对网页截图，返回文件路径。 */
    @GetMapping("/action/screenshot")
    public R<String> screenshot(@RequestParam String url) {
        return R.ok(actionService.screenshot(url));
    }

    /** 点击元素。 */
    @PostMapping("/action/click")
    public R<String> click(@RequestParam String url, @RequestParam String selector) {
        return R.ok(actionService.click(url, selector));
    }

    /** 输入文本。 */
    @PostMapping("/action/fill")
    public R<String> fill(@RequestParam String url,
                          @RequestParam String selector,
                          @RequestParam String text) {
        return R.ok(actionService.fill(url, selector, text));
    }

    /** 等待选择器出现。 */
    @GetMapping("/action/wait")
    public R<Boolean> waitFor(@RequestParam String url,
                              @RequestParam String selector,
                              @RequestParam(defaultValue = "10000") double timeout) {
        return R.ok(actionService.waitForSelector(url, selector, timeout));
    }

    /**
     * 自然语言驱动的 Browser Agent 入口。
     *
     * @param request 任务请求
     * @return Agent 的最终答复
     */
    @PostMapping("/agent/run")
    public R<String> run(@Valid @RequestBody AgentTaskRequest request) {
        return R.ok(agentService.run(request.getInstruction()));
    }
}