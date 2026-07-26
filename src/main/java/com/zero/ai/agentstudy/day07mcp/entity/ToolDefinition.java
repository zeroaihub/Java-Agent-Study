package com.zero.ai.agentstudy.day07mcp.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.Map;

/**
 * ToolDefinition —— 工具的「自我描述」，即 MCP tools/list 返回的每一项。
 *
 * <p>教学要点：MCP 里模型之所以能「自动选择工具」，靠的就是每个工具都携带一份
 * 机器可读的元数据：名字、说明、以及入参的 JSON Schema。模型读到这些描述后，
 * 才能判断「用户想查天气 → 应该调用 name=get_weather 的工具，参数 city=xxx」。</p>
 *
 * <p>字段说明：</p>
 * <ul>
 *   <li>{@code name}：工具唯一标识（模型调用时用它）；</li>
 *   <li>{@code description}：自然语言说明（给模型读，决定要不要用）；</li>
 *   <li>{@code inputSchema}：入参的 JSON Schema（约束参数结构）。
 *       MCP 规范里这个字段名是 "inputSchema"，用 {@code @JsonProperty} 固定。</li>
 * </ul>
 *
 * @author ZeroAi
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolDefinition {

    /** 工具唯一名称 */
    private String name;

    /** 给模型阅读的自然语言描述 */
    private String description;

    /** 入参的 JSON Schema（type/properties/required 等） */
    @JsonProperty("inputSchema")
    private Map<String, Object> inputSchema;

    /**
     * 便捷构造：生成一个「对象型」入参 schema。
     *
     * @param name        工具名
     * @param description 描述
     * @param properties  各参数的定义（key=参数名, value=其 schema）
     * @param required    必填参数名数组
     * @return 工具定义
     */
    public static ToolDefinition of(String name,
                                    String description,
                                    Map<String, Object> properties,
                                    String[] required) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", properties,
                "required", required
        );
        return ToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(schema)
                .build();
    }
}