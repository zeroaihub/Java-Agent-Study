package com.zero.ai.agentstudy.day07mcp.mcp.registry;

import com.zero.ai.agentstudy.day07mcp.entity.ToolDefinition;
import com.zero.ai.agentstudy.day07mcp.mcp.tool.McpTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ToolRegistry —— 工具注册中心（MCP Server 的「工具目录」）。
 *
 * <p>教学要点：这是「开闭原则」得以成立的关键装置。</p>
 * <ul>
 *   <li><b>自动收集</b>：构造器注入 {@code List<McpTool>}，Spring 会把容器里所有
 *       实现了 McpTool 的 Bean 自动装进来。于是「新增工具」= 新增一个 @Component，
 *       Registry / Server 代码完全不用改。</li>
 *   <li><b>按名索引</b>：把工具列表转成 name→tool 的 Map，tools/call 时 O(1) 定位。</li>
 *   <li><b>目录导出</b>：{@link #listDefinitions()} 汇总所有工具的元数据，
 *       正是 tools/list 要返回的内容。</li>
 * </ul>
 *
 * <p>设计原因：把「有哪些工具、怎么找工具」从 Server 里抽出来单独成类，
 * 符合单一职责——Server 只管协议分发，找工具的活交给 Registry。</p>
 *
 * @author ZeroAi
 */
@Slf4j
@Component
public class ToolRegistryDay07 {

    /** name → McpTool 的索引表（保持插入顺序，便于阅读日志） */
    private final Map<String, McpTool> toolMap = new LinkedHashMap<>();

    /**
     * 构造器注入所有 McpTool。
     *
     * <p>Spring 启动时会把容器内全部 McpTool 实现注入这个 List，
     * 我们在这里建立 name→tool 索引，并对「重名工具」做防御性检查。</p>
     *
     * @param tools 容器内所有工具（可能为空）
     */
    public ToolRegistryDay07(List<McpTool> tools) {
        if (tools != null) {
            for (McpTool tool : tools) {
                String name = tool.name();
                if (toolMap.containsKey(name)) {
                    // 重名会导致调用歧义，直接快速失败，避免上线后诡异问题
                    throw new IllegalStateException("发现重名工具：" + name
                            + "，请保证每个 McpTool.name() 唯一");
                }
                toolMap.put(name, tool);
                log.info("[ToolRegistry] 注册工具: {} -> {}", name, tool.getClass().getSimpleName());
            }
        }
        log.info("[ToolRegistry] 工具注册完成，共 {} 个：{}", toolMap.size(), toolMap.keySet());
    }

    /**
     * 按名称获取工具。
     *
     * @param name 工具名
     * @return 工具实例；不存在返回 null
     */
    public McpTool getTool(String name) {
        return toolMap.get(name);
    }

    /**
     * 判断工具是否存在。
     *
     * @param name 工具名
     * @return 存在返回 true
     */
    public boolean hasTool(String name) {
        return toolMap.containsKey(name);
    }

    /**
     * 导出所有工具的定义（即 tools/list 的返回内容）。
     *
     * @return 工具定义列表
     */
    public List<ToolDefinition> listDefinitions() {
        List<ToolDefinition> list = new ArrayList<>();
        for (McpTool tool : toolMap.values()) {
            list.add(tool.definition());
        }
        return list;
    }

    /**
     * 返回所有工具名（只读）。
     *
     * @return 工具名集合
     */
    public List<String> toolNames() {
        return Collections.unmodifiableList(new ArrayList<>(toolMap.keySet()));
    }
}