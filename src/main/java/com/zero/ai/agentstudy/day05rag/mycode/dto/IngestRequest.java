package com.zero.ai.agentstudy.day05rag.mycode.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 文档写入请求：把一段知识灌入向量库。
 *
 * <p>对应「离线索引流」的入口：title + content 会被切分 → 向量化 → 存储。
 */
@Data
public class IngestRequest {

    /** 文档标题（作为出处标识） */
    @NotBlank(message = "title 不能为空")
    private String title;

    /** 文档正文（会被自动切分成多个 Chunk） */
    @NotBlank(message = "content 不能为空")
    private String content;
}