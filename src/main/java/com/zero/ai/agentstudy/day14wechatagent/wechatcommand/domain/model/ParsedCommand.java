package com.zero.ai.agentstudy.day14wechatagent.wechatcommand.domain.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 已解析的命令（值对象）。
 * <p>
 * 由 {@code CommandParser} 从原始文本（如 {@code /book room=301 time=14:00 会议评审}）
 * 解析而来，是命令系统内部流转的结构化输入：
 * <ul>
 *     <li>{@link #name}：命令名，已去除前缀 {@code /} 并统一小写（如 {@code book}）。</li>
 *     <li>{@link #options}：键值参数，如 {@code room=301}、{@code time=14:00}。</li>
 *     <li>{@link #argument}：位置参数 / 自由文本（键值对之外剩余的原始内容）。</li>
 *     <li>{@link #raw}：原始命令文本，便于排障与审计。</li>
 * </ul>
 * 值对象不可变：{@code options} 在构造期即被包装为只读视图。
 *
 * @param name     命令名（不含前缀，小写）
 * @param options  键值参数（只读，保持插入顺序）
 * @param argument 位置参数 / 自由文本，可能为空字符串
 * @param raw      原始命令文本
 * @author ZeroAi
 */
public record ParsedCommand(
        String name,
        Map<String, String> options,
        String argument,
        String raw) {

    public ParsedCommand {
        name = name == null ? "" : name.strip().toLowerCase();
        argument = argument == null ? "" : argument.strip();
        raw = raw == null ? "" : raw;
        options = options == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(options));
    }

    /**
     * 取指定键值参数。
     *
     * @param key 参数名
     * @return 参数值；不存在返回 {@code null}
     */
    public String option(String key) {
        return options.get(key);
    }

    /**
     * 取指定键值参数，缺省回退。
     *
     * @param key          参数名
     * @param defaultValue 缺省值
     * @return 参数值或缺省值
     */
    public String option(String key, String defaultValue) {
        String value = options.get(key);
        return value == null ? defaultValue : value;
    }

    /**
     * 是否携带指定键值参数。
     *
     * @param key 参数名
     * @return 存在返回 {@code true}
     */
    public boolean hasOption(String key) {
        return options.containsKey(key);
    }

    /**
     * 是否携带位置参数 / 自由文本。
     *
     * @return 非空返回 {@code true}
     */
    public boolean hasArgument() {
        return !argument.isEmpty();
    }
}