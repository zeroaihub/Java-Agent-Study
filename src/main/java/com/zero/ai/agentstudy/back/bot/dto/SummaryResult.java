package com.zero.ai.agentstudy.back.bot.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 对话总结结果(结构化输出)
 * 对应知识点8: JSON 输出是 Agent 的基础
 *
 * @author ZeroAi
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SummaryResult {

    /** 一句话总结 */
    private String summary;

    /** 关键要点 */
    private List<String> keyPoints;

    /** 情感倾向: positive / neutral / negative */
    private String sentiment;
}
