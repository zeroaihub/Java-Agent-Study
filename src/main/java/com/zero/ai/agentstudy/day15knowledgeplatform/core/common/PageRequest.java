package com.zero.ai.agentstudy.day15knowledgeplatform.core.common;

/**
 * 分页请求值对象（不可变）。
 *
 * <p>page 从 0 开始；size 限制在 [1, MAX_SIZE]，避免一次性拉取过多数据拖垮数据库/向量库。</p>
 *
 * @param page 页码，从 0 开始
 * @param size 每页大小
 * @author ZeroAi
 */
public record PageRequest(int page, int size) {

    /**
     * 单页最大条数，防止恶意大分页。
     */
    public static final int MAX_SIZE = 200;

    public PageRequest {
        if (page < 0) {
            throw KnowledgeException.of(KnowledgeErrorCode.INVALID_ARGUMENT, "page 不能小于 0");
        }
        if (size < 1) {
            throw KnowledgeException.of(KnowledgeErrorCode.INVALID_ARGUMENT, "size 不能小于 1");
        }
        if (size > MAX_SIZE) {
            throw KnowledgeException.of(KnowledgeErrorCode.INVALID_ARGUMENT, "size 不能超过 " + MAX_SIZE);
        }
    }

    /**
     * 便捷工厂：默认第 0 页。
     */
    public static PageRequest of(int page, int size) {
        return new PageRequest(page, size);
    }

    /**
     * 转换为 SQL/存储层偏移量。
     */
    public long offset() {
        return (long) page * size;
    }
}