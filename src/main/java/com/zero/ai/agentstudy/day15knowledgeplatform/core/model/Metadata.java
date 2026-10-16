package com.zero.ai.agentstudy.day15knowledgeplatform.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 元数据值对象（不可变）。
 *
 * <p>贯穿文档与切片，承载用于过滤检索的结构化属性，例如：
 * {@code source=confluence}、{@code department=finance}、{@code lang=zh}、{@code version=3}。</p>
 *
 * <p>在 Hybrid Search 阶段，这些元数据会被下推为向量库的 metadata filter，
 * 从而实现「先过滤再召回」的权限感知与业务过滤。</p>
 *
 * <p>不可变：所有修改方法都返回新的 {@link Metadata} 实例。</p>
 *
 * @author ZeroAi
 */
public final class Metadata {

    private static final Metadata EMPTY = new Metadata(Map.of());

    private final Map<String, Object> attributes;

    private Metadata(Map<String, Object> attributes) {
        // 保序 + 不可变
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /**
     * 空元数据（单例）。
     */
    public static Metadata empty() {
        return EMPTY;
    }

    /**
     * 从现有 Map 构造。
     */
    public static Metadata of(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
         return EMPTY;
        }
        return new Metadata(attributes);
    }

    /**
     * 追加/覆盖单个键，返回新实例。
     */
    public Metadata put(String key, Object value) {
        LinkedHashMap<String, Object> next = new LinkedHashMap<>(this.attributes);
        next.put(key, value);
        return new Metadata(next);
    }

    /**
     * 合并另一份元数据（other 覆盖同名键），返回新实例。
     */
    public Metadata merge(Metadata other) {
        if (other == null || other.attributes.isEmpty()) {
            return this;
        }
        LinkedHashMap<String, Object> next = new LinkedHashMap<>(this.attributes);
        next.putAll(other.attributes);
        return new Metadata(next);
    }

    /**
     * 读取字符串值。
     */
    public Optional<String> getString(String key) {
        Object v = attributes.get(key);
        return v == null ? Optional.empty() : Optional.of(String.valueOf(v));
    }

    /**
     * 原始只读视图。
     */
    public Map<String, Object> asMap() {
        return attributes;
    }

    public boolean isEmpty() {
        return attributes.isEmpty();
    }

    @Override
    public String toString() {
        return "Metadata" + attributes;
    }
}