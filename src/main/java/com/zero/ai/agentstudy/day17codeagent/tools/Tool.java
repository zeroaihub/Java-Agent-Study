package com.zero.ai.agentstudy.day17codeagent.tools;

import com.zero.ai.agentstudy.day17codeagent.core.model.CodeTaskContext;

import java.util.List;
import java.util.Map;

/**
 * Code Agent 工具端口。
 *
 * <p>一个工具 = 「LLM 可以主动调用的一个动作」。它的设计要点：</p>
 * <ol>
 *     <li><b>名字与描述要给 LLM 看</b>：{@code description()} 会被拼进系统提示词，
 *         写得含糊，模型就会乱调。</li>
 *     <li><b>参数用 Map 而非强类型</b>：因为参数是模型生成的文本，本质是弱类型。
 *         由工具自己做解析与校验，并把「参数该怎么写」通过 {@link #usage()} 告诉模型。</li>
 *     <li><b>统一返回 {@link ToolResult}</b>：失败不外抛异常。</li>
 * </ol>
 *
 * @author ZeroAi
 */
public interface Tool {

    /** 工具名（英文短名，LLM 用它来指代）。 */
    String name();

    /** 给 LLM 看的一句话描述，会被拼进提示词。 */
    String description();

    /** 参数用法说明，例如 {@code {"path":"文件相对路径","from":"起始行(可选)"}}。 */
    List<String> parameters();

    /** 调用入口。 */
    ToolResult invoke(CodeTaskContext context, Map<String, String> args);

    /** 拼给 LLM 看的工具描述片段。 */
    default String usage() {
        StringBuilder sb = new StringBuilder();
        sb.append("- ").append(name()).append("：").append(description());
        if (!parameters().isEmpty()) {
            sb.append("\n    参数: ").append(String.join(", ", parameters()));
        }
        return sb.toString();
    }
}
