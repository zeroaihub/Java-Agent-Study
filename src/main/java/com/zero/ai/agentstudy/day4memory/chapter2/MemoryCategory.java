package com.zero.ai.agentstudy.day4memory.chapter2;

/**
 * Day4 第二章：Memory 分类。
 */
public enum MemoryCategory {

    SHORT_TERM(
            "short-term",
            "短期记忆",
            "保存最近几轮对话，用于理解当前上下文。",
            "Redis / 内存窗口",
            "分钟到小时级",
            "用户刚才说订单号是 A1001。",
            "AI 客服记住当前工单中的订单号、退款诉求。"
    ),

    LONG_TERM(
            "long-term",
            "长期记忆",
            "保存稳定、可复用的用户画像信息。",
            "MySQL / PostgreSQL",
            "天、月、年级",
            "用户是 Java 工程师，目标是成为 AI Agent 架构师。",
            "AI 学习助手长期记住用户职业、技能、学习目标。"
    ),

    WORKING(
            "working",
            "工作记忆",
            "保存当前任务执行过程中的临时状态。",
            "Redis / 任务状态表",
            "任务执行期间",
            "当前正在生成周报，已完成项目进展，还差风险项。",
            "AI 办公助手在多步骤任务中记录当前步骤和待办动作。"
    ),

    SEMANTIC(
            "semantic",
            "语义记忆",
            "保存抽象知识、事实、经验和规则。",
            "向量库 / 知识库 / 文档库",
            "长期，但需要版本管理",
            "Redis 适合缓存，MySQL 适合事务存储。",
            "AI 知识库记住企业制度、产品文档、技术规范。"
    );

    private final String code;
    private final String title;
    private final String definition;
    private final String typicalStorage;
    private final String lifecycle;
    private final String lifeExample;
    private final String agentExample;

    MemoryCategory(String code,
                   String title,
                   String definition,
                   String typicalStorage,
                   String lifecycle,
                   String lifeExample,
                   String agentExample) {
        this.code = code;
        this.title = title;
        this.definition = definition;
        this.typicalStorage = typicalStorage;
        this.lifecycle = lifecycle;
        this.lifeExample = lifeExample;
        this.agentExample = agentExample;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public String getDefinition() {
        return definition;
    }

    public String getTypicalStorage() {
        return typicalStorage;
    }

    public String getLifecycle() {
        return lifecycle;
    }

    public String getLifeExample() {
        return lifeExample;
    }

    public String getAgentExample() {
        return agentExample;
    }
}

