package com.zero.ai.agentstudy.day13officeagent.officecore.domain.port;

import com.zero.ai.agentstudy.day13officeagent.officecore.domain.context.TenantContext;

import java.util.List;
import java.util.Map;

/**
 * 知识库端口（KnowledgeStore）——出站端口。
 *
 * <p>终极场景中"同时保存到知识库"依赖此端口：把生成的周报切分、向量化后写入向量库，
 * 供后续 RAG 检索。它复用 Day1~Day12 已建的 RAG 能力（pgvector），此处只暴露领域所需的
 * 最小契约——入库与检索，屏蔽 Embedding 模型与向量库细节。</p>
 *
 * @author zero
 */
public interface KnowledgeStore {

    /**
     * 将一段文本文档入库（自动切分与向量化由适配器完成）。
     *
     * @param tenant   租户上下文，用于知识库隔离
     * @param docId    文档标识
     * @param content  文本内容
     * @param metadata 附加元数据（标题、来源、标签等）
     * @return 入库结果
     */
    IngestResult ingest(TenantContext tenant, String docId, String content,
                        Map<String, String> metadata);

    /**
     * 语义检索。
     *
     * @param tenant 租户上下文
     * @param query  查询文本
     * @param topK   返回条数
     * @return 命中片段列表
     */
    List<Hit> search(TenantContext tenant, String query, int topK);

    /**
     * 入库结果值对象。
     *
     * @param docId      文档标识
     * @param chunkCount 生成的切片数
     * @author zero
     */
    record IngestResult(String docId, int chunkCount) {
    }

    /**
     * 检索命中片段值对象。
     *
     * @param chunkId 切片标识
     * @param text    命中文本
     * @param score   相似度得分
     * @author zero
     */
    record Hit(String chunkId, String text, double score) {
    }
}