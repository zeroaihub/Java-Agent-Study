package com.zero.ai.agentstudy.day4memory.chapter6;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 教学版用户画像信号抽取器。
 *
 * 生产环境可以用 LLM 结构化抽取 + 人工确认 + 置信度。
 */
@Component
public class ProfileSignalExtractor {

    public void updateProfile(Chapter6UserProfile profile, String message) {
        if (message.contains("我叫")) {
            String name = extractAfter(message, "我叫", 12);
            profile.setName(cleanName(name));
        }
        if (message.contains("Java") || message.contains("后端") || message.contains("工程师")) {
            profile.setProfession("Java 后端工程师");
        }
        profile.mergeInterests(extractInterests(message));
        profile.mergeLearningGoals(extractGoals(message));
        profile.mergePreferences(extractPreferences(message));
    }

    private List<String> extractInterests(String message) {
        List<String> interests = new ArrayList<>();
        if (containsAny(message, "Agent", "智能体")) {
            interests.add("AI Agent");
        }
        if (containsAny(message, "RAG", "知识库")) {
            interests.add("RAG");
        }
        if (containsAny(message, "量化", "交易")) {
            interests.add("AI 量化交易");
        }
        if (message.contains("Memory")) {
            interests.add("Agent Memory");
        }
        return interests;
    }

    private List<String> extractGoals(String message) {
        List<String> goals = new ArrayList<>();
        if (containsAny(message, "架构师", "商业化", "产品")) {
            goals.add("成为 AI Agent 架构师并开发商业化 Agent 产品");
        }
        return goals;
    }

    private List<String> extractPreferences(String message) {
        List<String> preferences = new ArrayList<>();
        if (containsAny(message, "Java 优先", "Java为主", "Spring")) {
            preferences.add("Java 优先");
        }
        if (containsAny(message, "企业案例", "真实项目")) {
            preferences.add("结合企业真实案例");
        }
        return preferences;
    }

    private String extractAfter(String text, String marker, int maxLength) {
        int start = text.indexOf(marker);
        if (start < 0) {
            return "";
        }
        String value = text.substring(start + marker.length()).trim();
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String cleanName(String raw) {
        return raw.replaceAll("[，。,\\. ].*$", "").trim();
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

