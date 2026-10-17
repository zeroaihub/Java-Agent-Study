package com.zero.ai.agentstudy.day17codeagent.api.dto;

import java.util.List;

/**
 * 编码任务的响应体。
 *
 * <p>只返回 {@code success: true/false} 的 API 是没法用的。前端必须能知道：</p>
 * <ul>
 *     <li>改了哪些文件（用于渲染 diff）；</li>
 *     <li>验证到了什么程度（编译 / 测试）；</li>
 *     <li>有没有必须修改的评审问题（决定能不能合并）；</li>
 *     <li>traceId（出问题时能查到全链路）。</li>
 * </ul>
 *
 * @param taskId          任务 ID
 * @param traceId         追踪 ID
 * @param status          状态
 * @param mergeable       是否建议合并
 * @param changedFiles    变更文件清单
 * @param addedLines      新增行数
 * @param removedLines    删除行数
 * @param riskLevel       整体风险
 * @param compiled        是否编译通过
 * @param testSummary     测试摘要
 * @param reviewBlockers  必须修改的评审问题
 * @param concerns        风险提示
 * @param pullRequestBody PR 草稿正文
 * @param elapsedMs       耗时
 * @param tokens          token 消耗
 * @author ZeroAi
 */
public record CodeTaskResponse(
        String taskId,
        String traceId,
        String status,
        boolean mergeable,
        List<String> changedFiles,
        int addedLines,
        int removedLines,
        String riskLevel,
        boolean compiled,
        String testSummary,
        List<String> reviewBlockers,
        List<String> concerns,
        String pullRequestBody,
        long elapsedMs,
        int tokens
) {
    public static CodeTaskResponse failed(String taskId, String traceId, String status,
                                          List<String> concerns) {
        return new CodeTaskResponse(taskId, traceId, status, false,
                List.of(), 0, 0, "无", false, null, List.of(),
                concerns, null, 0, 0);
    }
}
