package com.zero.ai.agentstudy.day12longrunningagent.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Trending 外部世界桩（Stub）。
 *
 * <p>把"登录 / 抓取 / 总结 / 推送"这四个真实世界的重外部依赖，收敛到<b>一个可替换的
 * 接口点</b>。教学环境用内存假数据，让整条流水线零外部依赖即可跑通、可断言、可测试；
 * 生产环境把本类替换/子类化为真实实现即可：</p>
 * <ul>
 *   <li>{@link #login()}          -> Playwright 打开浏览器登录 GitHub</li>
 *   <li>{@link #fetchTrending()}  -> Playwright 抓取 trending 页面 DOM</li>
 *   <li>{@link #summarize(List)}  -> Spring AI 调 LLM 生成中文摘要</li>
 *   <li>{@link #notifyWeCom(String)} -> HTTP 调企业微信机器人 Webhook</li>
 * </ul>
 *
 * <p>为什么值得为"假数据"单独建一个类？因为它把<b>不确定性（网络、浏览器、大模型）
 * 隔离在系统边界</b>。核心流程（Handler/Runtime/Scheduler）因此变成纯确定性逻辑，
 * 可被稳定地单元测试——这是"六边形架构 / 端口与适配器"的核心思想。</p>
 */
@Component
public class TrendingStubClient {

    private static final Logger log = LoggerFactory.getLogger(TrendingStubClient.class);

    /**
     * 模拟登录。可通过 {@link #setFailLogin(boolean)} 触发失败，用于演示重试/死信。
     */
    private volatile boolean failLogin = false;

    public void login() {
        if (failLogin) {
            throw new RuntimeException("模拟登录失败（触发重试链路演示）");
        }
        sleep(50);
        log.info("[Stub] GitHub 登录成功（模拟）");
    }

    /** 模拟抓取 Trending 列表，返回若干项目名。 */
    public List<String> fetchTrending() {
        sleep(80);
        List<String> repos = List.of(
                "langchain-ai/langchain",
                "run-llama/llama_index",
                "microsoft/autogen",
                "openai/openai-cookbook",
                "vllm-project/vllm");
        log.info("[Stub] 抓取 Trending 成功（模拟），共 {} 项", repos.size());
        return repos;
    }

    /** 模拟 LLM 总结。 */
    public String summarize(List<String> repos) {
        sleep(120);
        String summary = "今日 GitHub AI Trending 简报：\n"
                + "共 " + (repos == null ? 0 : repos.size()) + " 个热门项目，"
                + "涵盖 LLM 应用框架、Agent 编排与高性能推理三大方向。";
        log.info("[Stub] LLM 总结成功（模拟）");
        return summary;
    }

    /** 模拟推送企业微信。 */
    public void notifyWeCom(String summary) {
        sleep(30);
        log.info("[Stub] 企业微信推送成功（模拟）：\n{}", summary);
    }

    public void setFailLogin(boolean failLogin) {
        this.failLogin = failLogin;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}