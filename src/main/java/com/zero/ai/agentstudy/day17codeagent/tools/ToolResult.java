package com.zero.ai.agentstudy.day17codeagent.tools;

import java.util.Map;

/**
 * 工具执行结果。
 *
 * <h3>本模块最核心的一条设计约定</h3>
 * <blockquote>
 *     <b>工具失败是返回值，不是异常。</b>
 * </blockquote>
 *
 * <p>Code Agent 的每一次工具调用都可能失败：文件不存在、正则写错、命令被拒绝、进程超时。
 * 这些都属于<b>可预期的失败</b>——它们的处理方式不是「终止整个任务」，而是
 * 「把失败信息喂回 LLM，让它下一次换个姿势试」。这条决定了 Agent 能不能自我修复。</p>
 *
 * <p>所以 {@code ToolResult} 必须同时能表达成功与失败，并且失败时要带上<b>足够让模型改正的信息</b>
 * （{@code error} 里写明「文件不存在：xxx，可用路径清单是 ……」），而不是一句笼统的 FAILED。</p>
 *
 * @param toolName  工具名
 * @param success   是否成功
 * @param output    成功输出（给 LLM 看的内容）
 * @param error     失败原因
 * @param elapsedMs 耗时
 * @author ZeroAi
 */
public record ToolResult(
        String toolName,
        boolean success,
        String output,
        String error,
        long elapsedMs
) {

    public static ToolResult success(String toolName, String output, long elapsedMs) {
        return new ToolResult(toolName, true, output, null, elapsedMs);
    }

    public static ToolResult failure(String toolName, String error, long elapsedMs) {
        return new ToolResult(toolName, false, null, error, elapsedMs);
    }

    /** 失败时是否具体到「可执行建议」级别（简单启发式：错误里带冒号或建议措辞）。 */
    public boolean actionable() {
        return error != null && (error.contains("建议") || error.contains("请") || error.contains(":"));
    }

    @Override
    public String toString() {
        if (success) {
            return toolName + "(OK, " + elapsedMs + "ms)";
        }
        return toolName + "(FAIL, " + elapsedMs + "ms): " + error;
    }
}
