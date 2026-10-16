package com.zero.ai.agentstudy.day14wechatagent.wechatcommand.application;

import com.zero.ai.agentstudy.day14wechatagent.wechatcommand.domain.model.ParsedCommand;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 命令解析器（Command Parser）。
 * <p>
 * 将原始命令文本解析为结构化的 {@link ParsedCommand}。支持的语法形如：
 * <pre>
 *   /book room=301 time=14:00 项目评审会
 *   /help
 *   /reset
 * </pre>
 * 解析规则：
 * <ol>
 *     <li>去除首部空白与前缀 {@code /}。</li>
 *     <li>第一个空白前的 token 作为命令名（小写）。</li>
 *     <li>其余 token 中，形如 {@code key=value} 的作为键值参数；</li>
 *     <li>不含 {@code =} 的 token 归入位置参数 / 自由文本（按空格拼接）。</li>
 * </ol>
 * 解析器是纯函数、无状态，线程安全。
 *
 * @author ZeroAi
 */
@Component
public class CommandParser {

    /** 命令前缀。 */
    public static final String PREFIX = "/";

    /**
     * 判断文本是否为命令（以 {@code /} 开头）。
     *
     * @param text 文本
     * @return 是命令返回 {@code true}
     */
    public boolean isCommand(String text) {
        return text != null && text.strip().startsWith(PREFIX);
    }

    /**
     * 解析命令文本。
     *
     * @param text 原始命令文本（应以 {@code /} 开头）
     * @return 解析结果；非命令或空命令返回命令名为空的 {@link ParsedCommand}
     */
    public ParsedCommand parse(String text) {
        String raw = text == null ? "" : text.strip();
        if (!raw.startsWith(PREFIX)) {
            return new ParsedCommand("", Map.of(), "", raw);
        }

        // 去前缀，按空白切分
        String body = raw.substring(PREFIX.length()).strip();
        if (body.isEmpty()) {
            return new ParsedCommand("", Map.of(), "", raw);
        }

        String[] tokens = body.split("\\s+");
        String name = tokens[0];

        Map<String, String> options = new LinkedHashMap<>();
        StringBuilder argument = new StringBuilder();
        for (int i = 1; i < tokens.length; i++) {
            String token = tokens[i];
            int eq = token.indexOf('=');
            if (eq > 0) {
                String key = token.substring(0, eq).strip();
                String value = token.substring(eq + 1).strip();
                if (!key.isEmpty()) {
                    options.put(key, value);
                    continue;
                }
            }
            // 非键值 token 归入自由文本
            if (!argument.isEmpty()) {
                argument.append(' ');
            }
            argument.append(token);
        }

        return new ParsedCommand(name, options, argument.toString(), raw);
    }
}