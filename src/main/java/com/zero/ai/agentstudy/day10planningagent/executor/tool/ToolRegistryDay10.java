package com.zero.ai.agentstudy.day10planningagent.executor.tool;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具注册表：Spring 自动注入所有 Tool 实现，按名字建索引。
 * 新增工具只需实现 Tool + @Component，无需改动注册表。
 */
@Component
public class ToolRegistryDay10 {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistryDay10(List<Tool> toolList) {
        for (Tool t : toolList) {
            tools.put(t.name(), t);
        }
    }

    public Optional<Tool> find(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(tools.get(name));
    }

    public boolean contains(String name) {
        return name != null && tools.containsKey(name);
    }

    /** 所有工具的能力描述，供提示词/日志使用。 */
    public String capabilities() {
        StringBuilder sb = new StringBuilder();
        tools.values().forEach(t ->
                sb.append("- ").append(t.name()).append(": ").append(t.description()).append("\n"));
        return sb.toString();
    }

    public java.util.Collection<Tool> all() {
        return tools.values();
    }
}