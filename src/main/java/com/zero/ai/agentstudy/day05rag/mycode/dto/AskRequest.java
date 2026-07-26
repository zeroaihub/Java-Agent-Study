package com.zero.ai.agentstudy.day05rag.mycode.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 提问请求：用户输入一个问题，系统召回资料并让 LLM 基于资料作答。
 *
 * <p>对应「在线问答流」的入口。
 */
@Data
public class AskRequest {

    /** 用户的问题 */
    @NotBlank(message = "question 不能为空")
    private String question;

    /** 召回 Top-K（可选，默认 3）。取相似度最高的前 K 个 Chunk。 */
    private Integer topK;
}