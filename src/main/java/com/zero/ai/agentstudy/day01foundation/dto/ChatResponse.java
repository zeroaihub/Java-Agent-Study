package com.zero.ai.agentstudy.day01foundation.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天响应结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {

    /** AI 生成的回答 */
    private String answer;

    /** 实际使用的模型名称 */
    private String model;

    /** 本次请求耗时（毫秒） */
    private long costMs;
}