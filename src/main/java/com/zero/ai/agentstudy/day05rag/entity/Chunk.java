package com.zero.ai.agentstudy.day05rag.entity;

import java.util.HashMap;
import java.util.Map;

/**
 * Chunk —— 向量库里的一条记录，承载第二章「铁律②」的三件套。
 *
 * <p>为什么这样设计：</p>
 * <p>RAG 召回后既要「算相似度」又要「把原文喂给 LLM」还要「告诉用户出处」，
 * 所以一条记录必须同时携带：向量(vector) + 原文(content) + 元数据(metadata)，
 * 外加一个唯一 id 便于增删改。缺任何一个，RAG 链路都会断。</p>
 *
 * @author ZeroAi
 */
public class Chunk {

    /** 唯一标识，便于按 id 增删改，如 "chunk-001" */
    private String id;

    /** 原文文本：召回后拼进 Prompt 喂给 LLM 的正是它 */
    private String content;

    /** 向量：由 Embedding 模型生成，用来算相似度 */
    private float[] vector;

    /** 元数据：来源文件、页码、部门等，用于溯源与过滤 */
    private Map<String, Object> metadata;

    public Chunk() {
        this.metadata = new HashMap<>();
    }

    /**
     * 常用构造：一次性传入三件套。
     *
     * @param id      唯一标识
     * @param content 原文文本
     * @param vector  向量
     */
    public Chunk(String id, String content, float[] vector) {
        this.id = id;
        this.content = content;
        this.vector = vector;
        this.metadata = new HashMap<>();
    }

    /**
     * 便捷方法：链式添加一条元数据。
     *
     * @param key   元数据键，如 "source"
     * @param value 元数据值，如 "员工手册.pdf"
     * @return this，支持链式调用
     */
    public Chunk addMetadata(String key, Object value) {
        this.metadata.put(key, value);
        return this;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public float[] getVector() {
        return vector;
    }

    public void setVector(float[] vector) {
        this.vector = vector;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    @Override
    public String toString() {
        return "Chunk{id='" + id + "', content='" + content + "', metadata=" + metadata + "}";
    }
}