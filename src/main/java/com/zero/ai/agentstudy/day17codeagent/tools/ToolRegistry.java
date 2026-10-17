package com.zero.ai.agentstudy.day17codeagent.tools;

import java.util.List;
import java.util.Map;

/**
 * 工具注册表：Agent 唯一能拿到工具的地方。
 *
 * <p>为什么要注册表而不是一堆 if-else：工具清单需要<b>动态拼进提示词</b>。
 * 有了注册表，「给这次任务开放哪些工具」就变成一行配置——
 * 只读分析任务不注册 {@code write_file}，模型就<b>根本不知道有这个工具存在</b>，
 * 比「注册了但在调用时拒绝」安全得多（后者会被诱导反复重试）。</p>
 *
 * @author ZeroAi
 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new java.util.LinkedHashMap<>();

    public ToolRegistry register(Tool tool) {
        if (tool == null) {
            return this;
        }
        Tool previous = tools.put(tool.name(), tool);
        if (previous != null) {
            throw new IllegalStateException("工具名重复：" + tool.name());
        }
        return this;
    }

    /** 是否注册了某个工具。 */
    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    /** 取工具，不存在返回 null（由调用方决定如何降级）。 */
    public Tool find(String name) {
        return tools.get(name);
    }

    public List<Tool> all() {
        return List.copyOf(tools.values());
    }

    public List<String> names() {
        return List.copyOf(tools.keySet());
    }

    /**
     * 生成给 LLM 看的工具清单。
     *
     * <p>顺序稳定（LinkedHashMap）：LLM 对输入顺序敏感，工具清单每轮乱序会让同样任务产出不同结果。</p>
     */
    public String describe() {
        StringBuilder sb = new StringBuilder("可用工具：\n");
        for (Tool tool : tools.values()) {
            sb.append(tool.usage()).append('\n');
        }
        return sb.toString();
    }

    /** 只读工具集合，用于「只读分析」模式。 */
    public static ToolRegistry readOnly(com.zero.ai.agentstudy.day17codeagent.repo.VirtualFileSystem vfs) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ListFilesTool(vfs))
                .register(new ReadFileTool(vfs))
                .register(new SearchTool(vfs));
        return registry;
    }

    /** 完整工具集合。 */
    public static ToolRegistry full(com.zero.ai.agentstudy.day17codeagent.repo.VirtualFileSystem vfs,
                                    CommandExecutor executor) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ListFilesTool(vfs))
                .register(new ReadFileTool(vfs))
                .register(new SearchTool(vfs))
                .register(new WriteFileTool(vfs))
                .register(new RunCommandTool(executor));
        return registry;
    }
}
