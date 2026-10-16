package com.zero.ai.agentstudy.day15knowledgeplatform.core.common;

import java.util.List;

/**
 * 分页结果值对象（不可变）。
 *
 * <p>用于文档列表、检索结果等需要分页返回的场景，携带总数便于前端渲染分页器。</p>
 *
 * @param content    当前页数据
 * @param page       当前页码（从 0 开始）
 * @param size       每页大小
 * @param totalElements 总条数
 * @param <T>        元素类型
 * @author ZeroAi
 */
public record Page<T>(List<T> content, int page, int size, long totalElements) {

    public Page {
        content = content == null ? List.of() : List.copyOf(content);
    }

    /**
     * 基于分页请求构造。
     */
    public static <T> Page<T> of(List<T> content, PageRequest request, long totalElements) {
        return new Page<>(content, request.page(), request.size(), totalElements);
    }

    /**
     * 空页。
     */
    public static <T> Page<T> empty(PageRequest request) {
        return new Page<>(List.of(), request.page(), request.size(), 0L);
    }

    /**
     * 总页数。
     */
    public int totalPages() {
        if (size <= 0) {
            return 0;
        }
        return (int) Math.ceil((double) totalElements / size);
    }

    /**
     * 是否还有下一页。
     */
    public boolean hasNext() {
        return page + 1 < totalPages();
    }
}