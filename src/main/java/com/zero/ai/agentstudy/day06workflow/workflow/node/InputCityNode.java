package com.zero.ai.agentstudy.day06workflow.workflow.node;

import com.zero.ai.agentstudy.day06workflow.workflow.context.ContextKeys;
import com.zero.ai.agentstudy.day06workflow.workflow.context.WorkflowContext;
import com.zero.ai.agentstudy.day06workflow.workflow.core.NodeResult;
import com.zero.ai.agentstudy.day06workflow.workflow.core.WorkflowNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * InputCityNode —— 第 1 个节点：从用户输入中解析目的地城市。
 *
 * <p>教学要点：这是责任链的第一环。它只负责一件事（SRP）：
 * 把自然语言里的城市抠出来放进 Context，供后续节点使用。</p>
 *
 * @author ZeroAi
 */
@Slf4j
@Component
public class InputCityNode implements WorkflowNode {

    /** 简单规则：匹配「去XX」「到XX」或直接城市名。真实项目可换成 LLM 抽取 */
    private static final Pattern CITY_PATTERN = Pattern.compile("(?:去|到|游|玩)\\s*([\\u4e00-\\u9fa5]{2,4})");

    @Override
    public String name() {
        return "InputCityNode";
    }

    @Override
    public NodeResult execute(WorkflowContext context) {
        String input = context.getString(ContextKeys.USER_INPUT);
        if (input == null || input.isBlank()) {
            return NodeResult.fail("用户输入为空，无法解析城市");
        }
        Matcher m = CITY_PATTERN.matcher(input);
        String city;
        if (m.find()) {
            city = m.group(1);
        } else {
            // 兜底：取输入前 2~4 个中文字符当城市
            String trimmed = input.replaceAll("[^\\u4e00-\\u9fa5]", "");
            city = trimmed.length() >= 2 ? trimmed.substring(0, Math.min(4, trimmed.length())) : "北京";
        }
        context.put(ContextKeys.CITY, city);
        log.info("[InputCityNode] 解析目的地城市 = {}", city);
        return NodeResult.success("解析到城市: " + city);
    }
}