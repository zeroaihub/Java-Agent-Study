package com.zero.ai.agentstudy.day14wechatagent.wechatmemory.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 知识文档（长期记忆的入库单元）。
 *
 * <p>企业把 FAQ、规章制度、产品手册、会议纪要等以「文档」为单位录入知识库。
 * 一篇 {@code KnowledgeDocument} 后续会被切片器（chunker）切成若干
 * {@link KnowledgeChunk}，再逐片向量化并入库。</p>
 *
 * <p>该 record 是不可变值对象，记录：文档 ID、租户、标题、正文、来源、创建时间。
 * {@code source} 用于标注文档出处（如「HR-制度」「会议纪要」「产品Wiki」），
 * 便于检索结果溯源与权限过滤。</p>
 *
 * @param documentId 文档唯一 ID
 * @param tenantId   租户（多租户隔离）
 * @param title      文档标题
 * @param content    文档正文
 * @param source     来源标签（如 "FAQ"、"会议纪要"）
 * @param createdAt  入库时间
 * @author ZeroHub AI Agent Platform
 */
public record KnowledgeDocument(
        String documentId,
        String tenantId,
        String title,
        String content,
        String source,
        Instant createdAt) {

    /**
     * 紧凑构造器：非空校验与文本规范化。
     */
    public KnowledgeDocument {
        Objects.requireNonNull(documentId, "documentId 不能为空");
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(content, "content 不能为空");
        title = title == null ? "" : title.strip();
        content = content.strip();
        source = source == null ? "unknown" : source.strip();
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /**
     * 新建一篇文档（自动生成 ID 与创建时间）。
     *
     * @param tenantId 租户
     * @param title    标题
     * @param content  正文
     * @param source   来源标签
     * @return 新文档
     */
    public static KnowledgeDocument create(String tenantId, String title, String content, String source) {
        return new KnowledgeDocument(
                UUID.randomUUID().toString(),
                tenantId,
                title,
                content,
                source,
                Instant.now());
    }

    /**
     * 粗略估算文档长度对应的切片数（按每片约 500 字符），用于日志与容量预估。
     *
     * @param chunkSize 切片字符数
     * @return 估算切片数（至少 1）
     */
    public int estimatedChunks(int chunkSize) {
        int size = Math.max(1, chunkSize);
        return Math.max(1, (content.length() + size - 1) / size);
    }
}