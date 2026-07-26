package com.zero.ai.agentstudy.day05rag.mycode.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 提问应：LLM 基于召回资料给出的答案，并附带引用出处。
 *
 * <p>「可追溯出处」是 RAG 相比微调的核心优势之一，所以这里显式返回召回片段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AskResponse {

    /** LLM 生成的最终答案 */
    private String answer;

    /** 本次召回并喂给 LLM 的资料片段（引用出处） */
    private List<Reference> references;

    /**
     * 单条引用出处：命中的原文片段 + 来自哪个文档 + 相似度分数。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Reference {

        /** 来源文档标题 */
        private String docTitle;

        /** 命中的原文片段 */
        private String content;

        /** 与问题的余弦相似度（0~1，越大越相关） */
        private double score;
    }
}