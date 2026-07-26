package com.zero.ai.agentstudy.day4memory.chapter3;

/**
 * Chat Memory 控制策略。
 */
public enum MemoryStrategy {

    FULL_HISTORY("full-history", "完整历史", "每次把所有历史消息都发给 LLM，最容易理解，但成本会持续上涨。"),
    MESSAGE_WINDOW("message-window", "滑动窗口", "只保留最近 N 条消息，成本可控，但旧上下文会丢失。"),
    SUMMARY_COMPRESSION("summary-compression", "摘要压缩", "把旧消息压缩成摘要，再拼接最近消息，兼顾成本和长期上下文。");

    private final String code;
    private final String title;
    private final String description;

    MemoryStrategy(String code, String title, String description) {
        this.code = code;
        this.title = title;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }
}

