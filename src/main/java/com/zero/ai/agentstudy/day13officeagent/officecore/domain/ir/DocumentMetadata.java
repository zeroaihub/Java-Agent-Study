package com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir;

import java.time.Instant;
import java.util.Map;

/**
 * 文档元数据（DocumentMetadata）值对象。
 *
 * <p>描述一份文档的"身份与出身"信息，独立于正文内容之外：标题、作者、创建时间、
 * 所属租户、以及可扩展的自定义属性。元数据会被渲染器写进 Office 文档的文档属性
 * （Document Properties），也会被知识库入库时用作检索字段，因此是打通"生成→归档→检索"
 * 链路的关键。</p>
 *
 * @param title      文档标题
 * @param author     作者/生成者
 * @param tenantId   所属租户标识，支撑多租户隔离
 * @param createdAt  创建时间
 * @param properties 自定义扩展属性，永不为 {@code null}
 * @author zero
 */
public record DocumentMetadata(String title, String author, String tenantId,
                               Instant createdAt, Map<String, String> properties) {

    public DocumentMetadata {
        title = title == null ? "" : title;
        author = author == null ? "" : author;
        tenantId = tenantId == null ? "default" : tenantId;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    /**
     * 快捷工厂：仅指定标题与作者，其余取默认值。
     *
     * @param title  标题
     * @param author 作者
     * @return 元数据实例
     */
    public static DocumentMetadata of(String title, String author) {
        return new DocumentMetadata(title, author, "default", Instant.now(), Map.of());
    }

    /**
     * 派生一个替换了标题的新元数据（不可变对象的写时复制）。
     *
     * @param newTitle 新标题
     * @return 新的元数据实例
     */
    public DocumentMetadata withTitle(String newTitle) {
        return new DocumentMetadata(newTitle, author, tenantId, createdAt, properties);
    }

    /**
     * 派生一个替换了租户的新元数据。
     *
     * @param newTenantId 新租户标识
     * @return 新的元数据实例
     */
    public DocumentMetadata withTenant(String newTenantId) {
        return new DocumentMetadata(title, author, newTenantId, createdAt, properties);
    }
}