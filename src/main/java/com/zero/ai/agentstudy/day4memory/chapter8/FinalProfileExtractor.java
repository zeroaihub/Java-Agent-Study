package com.zero.ai.agentstudy.day4memory.chapter8;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 最终 Agent 用户画像抽取器。
 */
@Component
public class FinalProfileExtractor {

    public void updateProfile(FinalUserProfile profile, String message) {
        String name = extractName(message);
        if (!name.isBlank()) {
            profile.setName(name);
        }
        if (containsAny(message, "Java", "后端工程师", "Spring Boot")) {
            profile.setProfession("Java 后端工程师");
            profile.mergePreferences(List.of("Java 优先", "Spring Boot 架构视角"));
        }
        if (containsAny(message, "AI Agent 架构师", "Agent架构师", "商业化Agent", "商业化 Agent")) {
            profile.mergeGoals(List.of("成为 AI Agent 架构师并开发商业化 Agent 产品"));
        }
        if (containsAny(message, "企业案例", "真实项目")) {
            profile.mergePreferences(List.of("结合企业真实案例"));
        }
        if (containsAny(message, "Python辅助", "Python 辅助")) {
            profile.mergePreferences(List.of("Python 作为辅助阅读开源项目"));
        }
    }

    private String extractName(String message) {
        List<String> markers = new ArrayList<>();
        markers.add("我叫");
        markers.add("我的名字是");
        markers.add("我是");
        for (String marker : markers) {
            int index = message.indexOf(marker);
            if (index >= 0) {
                String value = message.substring(index + marker.length()).trim();
                return clean(value);
            }
        }
        return "";
    }

    private String clean(String value) {
        String cleaned = value.replaceAll("[，。,\\. ].*$", "").trim();
        if (cleaned.length() > 12) {
            return "";
        }
        return cleaned;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}

