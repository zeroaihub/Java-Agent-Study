package com.zero.ai.agentstudy.day14wechatagent.wechatcommand.application;

import com.zero.ai.agentstudy.day14wechatagent.wechatcommand.domain.port.Command;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 命令注册表（Command Registry）。
 * <p>
 * 构造期通过 Spring 集合注入自动收集所有 {@link Command} 实现，建立
 * 「命令名 / 别名 → 命令」索引，实现「新增命令零配置」：只需新增一个
 * {@code @Component} 的 Command 实现，即自动可用，无需改动任何注册代码。
 * <p>
 * 索引在构造期一次性固化为不可变映射，运行期只读、无锁、线程安全。
 * 同名命令冲突时快速失败（抛出异常），避免运行期歧义。
 *
 * @author ZeroAi
 */
@Slf4j
@Component
public class CommandRegistry {

    /** name/alias -> Command，供分发查找（含别名）。 */
    private final Map<String, Command> index;

    /** name -> Command，仅主名，按字典序，供 /help 展示。 */
    private final Map<String, Command> commandsByName;

    public CommandRegistry(List<Command> commands) {
        Map<String, Command> idx = new LinkedHashMap<>();
        Map<String, Command> byName = new TreeMap<>();
        for (Command command : commands) {
            String name = normalize(command.name());
            if (name.isEmpty()) {
                throw new IllegalStateException(
                        "命令名不可为空：" + command.getClass().getName());
            }
            register(idx, name, command);
            byName.put(name, command);
            for (String alias : command.aliases()) {
                register(idx, normalize(alias), command);
            }
        }
        this.index = Map.copyOf(idx);
        this.commandsByName = java.util.Collections.unmodifiableMap(byName);
        log.info("[Command] 已注册 {} 条命令：{}", byName.size(), byName.keySet());
    }

    private void register(Map<String, Command> idx, String key, Command command) {
        if (key.isEmpty()) {
            return;
        }
        Command existing = idx.putIfAbsent(key, command);
        if (existing != null && existing != command) {
            throw new IllegalStateException("命令名/别名冲突：'" + key + "' 同时被 "
                    + existing.getClass().getName() + " 与 "
                    + command.getClass().getName() + " 占用");
        }
    }

    private String normalize(String key) {
        return key == null ? "" : key.strip().toLowerCase();
    }

    /**
     * 按名称 / 别名查找命令。
     *
     * @param name 命令名或别名
     * @return 命令，若不存在返回空
     */
    public Optional<Command> find(String name) {
        return Optional.ofNullable(index.get(normalize(name)));
    }

    /**
     * 是否存在指定命令。
     *
     * @param name 命令名或别名
     * @return 存在返回 {@code true}
     */
    public boolean contains(String name) {
        return index.containsKey(normalize(name));
    }

    /**
     * 所有命令（按主名字典序，去重），供 /help 遍历。
     *
     * @return 命令集合
     */
    public Collection<Command> all() {
        return commandsByName.values();
    }

    /**
     * 已注册命令数量（按主名计）。
     *
     * @return 数量
     */
    public int size() {
        return commandsByName.size();
    }
}