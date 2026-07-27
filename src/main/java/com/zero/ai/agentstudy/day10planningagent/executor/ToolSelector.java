package com.zero.ai.agentstudy.day10planningagent.executor;

import com.zero.ai.agentstudy.day10planningagent.core.PlanStep;
import com.zero.ai.agentstudy.day10planningagent.executor.tool.Tool;
import com.zero.ai.agentstudy.day10planningagent.executor.tool.ToolRegistry;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 工具选择器：优先用规划器建议的工具，否则按关键词兜底，最后兜底 llm。
 */
@Component
public class ToolSelector {

    private final ToolRegistry registry;

    public ToolSelector(ToolRegistry registry) {
        this.registry = registry;
    }

    public Tool select(PlanStep step) {
        // 1. 规划器建议的工具优先
        if (registry.contains(step.suggestedTool())) {
            return registry.find(step.suggestedTool()).orElseThrow();
        }

        // 2. 关键词兜底：动作里含抓取/网页/爬取等 → browser
        String action = step.action() == null ? "" : step.action().toLowerCase();
        if (containsAny(action, "抓取", "爬取", "网页", "页面", "fetch", "crawl", "http", "url", "trending")) {
            Optional<Tool> browser = registry.find("browser");
            if (browser.isPresent()) return browser.get();
        }

        // 3. 最终兜底：llm（若无 llm 则抛异常）
        return registry.find("llm").orElseThrow(
               () -> new IllegalStateException("无可用工具处理步骤: " + step.id()));
    }

    private boolean containsAny(String text, String... keys) {
        for (String k : keys) {
            if (text.contains(k.toLowerCase())) return true;
        }
        return false;
    }
}