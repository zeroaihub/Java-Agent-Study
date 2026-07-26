package com.zero.ai.agentstudy.day08multiagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * MockLlmClient —— {@link LlmClient} 的默认实现（基于规则的模拟大模型）。
 *
 * <p>教学要点：为了让整个 Multi-Agent 项目<b>开箱即运行、不依赖任何真实 API Key</b>，
 * 我们提供一个「假」的大模型：它不真正调用远程模型，而是根据 systemPrompt 里携带的
 * 「角色标识」返回结构合理、以假乱真的内容。这样学生 clone 下来就能跑通四个 Agent 的
 * 完整流水线，看到真实的协作过程，而不是卡在「没有 Key」这一步。</p>
 *
 * <p>切换真实模型：只要写一个 OpenAiLlmClient 实现 {@link LlmClient}，
 * 用 {@code @Primary} 或配置开关替换本 Bean 即可，Agent 代码一行都不用改。</p>
 *
 * <p>标注 {@code @Component}，Spring 启动时自动注册为默认 LlmClient。</p>
 *
 * @author ZeroAi
 */
@Slf4j
@Component
public class MockLlmClient implements LlmClient {

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        log.debug("[MockLLM] systemPrompt 前缀={}",
                systemPrompt == null ? "null"
                        : systemPrompt.substring(0, Math.min(20, systemPrompt.length())));
        String sp = systemPrompt == null ? "" : systemPrompt;

        // 根据角色标识分流，返回对应结构化模拟内容
        if (sp.contains("规划")) {
            return mockPlan(userPrompt);
        } else if (sp.contains("研究") || sp.contains("素材")) {
            return mockResearch(userPrompt);
        } else if (sp.contains("写作") || sp.contains("正文")) {
            return mockWrite(userPrompt);
        } else if (sp.contains("评审") || sp.contains("审校")) {
            return mockReview(userPrompt);
        }
        return "【MockLLM 通用回复】已收到请求：" + brief(userPrompt);
    }

    @Override
    public String name() {
        return "MockLlmClient";
    }

    /** 模拟「规划」：输出用 ||| 分隔的大纲小节，方便 PlannerAgent 解析 */
    private String mockPlan(String userPrompt) {
        String topic = extractTopic(userPrompt);
        return String.join("|||",
                "开篇引入：为什么要关注「" + topic + "」",
                "核心要点一：主流选择与特点",
                "核心要点二：适用场景与对比",
                "选型建议：如何根据需求挑选",
                "结语：总结与行动建议");
    }

    /** 模拟「研究」：为大纲每个小节生成一段素材 */
    private String mockResearch(String userPrompt) {
        return "【素材】围绕主题整理的关键事实、数据与案例（Mock 模拟）：\n"
                + "- 事实1：该领域近年快速发展，工具日益成熟；\n"
                + "- 事实2：不同工具在易用性、生态、成本上各有侧重；\n"
                + "- 案例：某团队采用后效率提升约 30%（示意数据）。";
    }

    /** 模拟「写作」：产出一篇结构完整的 Markdown 草稿 */
    private String mockWrite(String userPrompt) {
        String topic = extractTopic(userPrompt);
        return "# " + topic + "\n\n"
                + "## 开篇\n在信息爆炸的今天，围绕「" + topic + "」的话题备受关注。本文带你快速了解。\n\n"
                + "## 核心要点\n目前主流方案各有特点：有的胜在易用，有的胜在生态，有的胜在性价比。\n\n"
                + "## 适用场景\n初学者建议从上手成本低的方案开始；团队则应综合考虑协作与集成能力。\n\n"
                + "## 选型建议\n先明确自己的核心诉求（效率/成本/生态），再对号入座地选择。\n\n"
                + "## 结语\n没有最好的工具，只有最合适的选择。动手试用，才能找到属于你的答案。\n";
    }

    /** 模拟「评审」：输出「分数|评审意见」，ReviewerAgent 会解析首段分数 */
    private String mockReview(String userPrompt) {
        return "0.9|结构完整、逻辑清晰，覆盖了开篇、要点、场景、选型与结语；"
                + "建议在「核心要点」补充 1-2 个具体工具名与数据以增强说服力。整体达到发布标准。";
    }

    /** 从 userPrompt 中粗略提取主题（Mock 用途，取「主题：xxx」后内容或整体前 20 字） */
    private String extractTopic(String userPrompt) {
        if (userPrompt == null) {
            return "该主题";
        }
        int idx = userPrompt.indexOf("主题：");
        if (idx >= 0) {
            String rest = userPrompt.substring(idx + 3);
            int end = rest.indexOf('\n');
            return (end > 0 ? rest.substring(0, end) : rest).trim();
        }
        return userPrompt.length() > 20 ? userPrompt.substring(0, 20) : userPrompt;
    }

    /** 截断展示 */
    private String brief(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 30 ? s.substring(0, 30) + "..." : s;
    }
}