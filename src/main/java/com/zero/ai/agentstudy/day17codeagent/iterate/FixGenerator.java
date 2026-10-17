package com.zero.ai.agentstudy.day17codeagent.iterate;

import com.zero.ai.agentstudy.day17codeagent.patch.TextPatch;

import java.util.List;

/**
 * 修复补丁生成器（自修复循环的<code>Act</code>环节）。
 *
 * <p>把它定义成接口而非具体调用，是为了让循环本体与「谁来修复」解耦：</p>
 * <ul>
 *     <li>教程里用简单的规则匹配（可靠桩）；</li>
 *     <li>生产里是一个拼好修复提示词的 LLM 调用。</li>
 * </ul>
 *
 * @author ZeroAi
 */
public interface FixGenerator {

    /**
     * 依据编译反馈生成修复补丁。
     *
     * @param feedback 编译 / 测试的失败反馈文本
     * @param round    当前轮次（从 1 开始）
     * @return 补丁列表；返回空表示「模型放弃修复」
     */
    List<TextPatch> generateFixes(String feedback, int round);
}
