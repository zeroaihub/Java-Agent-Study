package com.zero.ai.agentstudy.day05rag.mycode.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 文档写入响应：告诉调用方这次灌入产生了多少个 Chunk、库里共有多少条。
 */
@Data
@AllArgsConstructor
public class IngestResponse {

    /** 本次写入的文档标题 */
    private String title;

    /** 本次切分并向量化的 Chunk 数量 */
    private int chunkCount;

    /** 当前向量库中的总条数 */
    private int totalInStore;
}