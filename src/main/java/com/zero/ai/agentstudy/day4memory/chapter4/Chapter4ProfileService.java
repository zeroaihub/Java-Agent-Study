package com.zero.ai.agentstudy.day4memory.chapter4;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 第四章：长期 Memory 设计服务。
 */
@Service
@RequiredArgsConstructor
public class Chapter4ProfileService {

    private final InMemoryUserProfileRepository repository;

    public ProfileSchemaResponse schema() {
        return new ProfileSchemaResponse(asciiDiagram(), fieldRules(), mysqlDdl());
    }

    public UserProfile upsert(UserProfilePatch patch) {
        requireText(patch.userId(), "userId");
        UserProfile profile = repository.findByUserId(patch.userId())
                .orElseGet(() -> UserProfile.empty(patch.userId()));
        profile.merge(patch);
        return repository.save(profile);
    }

    public UserProfile get(String userId) {
        requireText(userId, "userId");
        return repository.findByUserId(userId).orElseGet(() -> UserProfile.empty(userId));
    }

    public FieldDecisionResponse decide(String content) {
        requireText(content, "content");
        String text = content.trim();
        if (containsAny(text, "密码", "银行卡", "身份证", "token", "密钥", "验证码")) {
            return new FieldDecisionResponse(text, false, "none",
                    "该信息属于敏感信息，不应进入长期 Memory。",
                    "高风险：可能违反隐私与安全规范。");
        }
        if (containsAny(text, "我叫", "用户叫", "姓名")) {
            return new FieldDecisionResponse(text, true, "name",
                    "姓名是稳定身份信息，适合长期保存，但应允许用户修改和删除。",
                    "低风险：注意提供用户可见、可删能力。");
        }
        if (containsAny(text, "工程师", "架构师", "产品经理", "职业")) {
            return new FieldDecisionResponse(text, true, "profession",
                    "职业会影响回答风格和示例选择，适合作为长期画像。",
                    "中低风险：职业变化时需要更新。");
        }
        if (containsAny(text, "目标", "希望", "想成为", "学习")) {
            return new FieldDecisionResponse(text, true, "learningGoals",
                    "学习目标会长期影响 Agent 的教学路径，适合长期保存。",
                    "中低风险：需要更新时间，避免目标过期。");
        }
        if (containsAny(text, "喜欢", "偏好", "优先", "用 Java", "简洁")) {
            return new FieldDecisionResponse(text, true, "preferences",
                    "偏好能显著提升个性化体验，适合长期保存。",
                    "中低风险：偏好应支持覆盖和撤销。");
        }
        if (containsAny(text, "当前项目", "最近项目", "正在做")) {
            return new FieldDecisionResponse(text, true, "recentProjects",
                    "最近项目对短期个性化有价值，但应设置过期或更新时间。",
                    "中风险：项目状态容易过期。");
        }
        return new FieldDecisionResponse(text, false, "shortTermOnly",
                "该信息更像一次性上下文，默认只放入短期 Chat Memory。",
                "低风险：先不沉淀，避免长期画像污染。");
    }

    private List<ProfileFieldRule> fieldRules() {
        return List.of(
                new ProfileFieldRule("name", "适合长期保存", "稳定身份信息", "张三", "VARCHAR + 可修改/可删除"),
                new ProfileFieldRule("profession", "适合长期保存", "影响回答深度与案例选择", "Java 后端工程师", "VARCHAR + updated_at"),
                new ProfileFieldRule("skills", "适合长期保存", "用于个性化学习路径", "Spring Boot、MySQL", "JSON + 去重"),
                new ProfileFieldRule("interests", "适合长期保存", "用于推荐主题", "AI Agent、RAG", "JSON + 置信度"),
                new ProfileFieldRule("learningGoals", "适合长期保存", "决定长期教学计划", "成为 AI Agent 架构师", "JSON + 更新时间"),
                new ProfileFieldRule("preferences", "适合长期保存", "决定回答风格", "Java 优先、企业案例", "JSON + 用户可编辑"),
                new ProfileFieldRule("recentProjects", "谨慎长期保存", "近期有价值但容易过期", "Memory Agent Demo", "JSON + TTL/过期策略"),
                new ProfileFieldRule("password/token/idCard", "禁止长期保存", "敏感信息，安全风险高", "密码、API token", "默认拒绝入库")
        );
    }

    private String asciiDiagram() {
        return """
                UserProfile
                ├── Basic Info       name / profession
                ├── Skills           Java / Spring Boot / MySQL
                ├── Interests        AI Agent / RAG / Quant
                ├── Learning Goals   成为 AI Agent 架构师
                ├── Preferences      Java 优先 / 企业案例 / 简洁回答
                └── Recent Projects  当前或近期项目，需过期策略
                """;
    }

    private String mysqlDdl() {
        return """
                CREATE TABLE user_profile (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    user_id VARCHAR(64) NOT NULL UNIQUE,
                    name VARCHAR(64),
                    profession VARCHAR(128),
                    skills JSON,
                    interests JSON,
                    learning_goals JSON,
                    preferences JSON,
                    recent_projects JSON,
                    confidence DECIMAL(3,2) NOT NULL DEFAULT 0.80,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                );
                """;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private void requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
    }
}

