package com.zero.ai.agentstudy.day08multiagent.agent.memory;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * SharedMemory —— 多 Agent 协作的「共享记忆 / 黑板」。
 *
 * <p>教学要点：这是 Multi-Agent 系统里最关键的对象（对应第二章架构图中央那块黑板）。
 * 各 Agent 之间<b>不直接互相调用</b>，而是通过 SharedMemory 交换数据：
 * 上游 Agent 把产出 put 进来，下游 Agent 再 get 出去用。这就是「黑板模式」，
 * 它带来两大好处：</p>
 * <ul>
 *   <li><b>解耦</b>：WriterAgent 只关心「outline 和 materials 在不在」，
 *       不需要知道它们是哪个 Agent 产的；</li>
 *   <li><b>可追溯</b>：黑板本身就是一次协作的完整快照，随时可 dump 出来调试。</li>
 * </ul>
 *
 * <p>线程安全：用 {@link ConcurrentHashMap}，为将来「并行聚合」策略（多个 Agent 并发写）预留。</p>
 *
 * <p>键约定：为避免 Agent 之间「对不上暗号」，所有键集中定义为常量（见内部的 Keys）。
 * 这是第二章「避坑 2：黑板键要有约定」的落地。</p>
 *
 * @author ZeroAi
 */
@Slf4j
public class SharedMemory {

    /**
     * 黑板键约定。所有 Agent 读写共享数据时，<b>必须</b>使用这里的常量，禁止裸写字符串。
     */
    public static final class Keys {
        private Keys() {
        }

        /** 写作大纲（由 PlannerAgent 产出，List<String>） */
        public static final String OUTLINE = "outline";

        /** 收集到的素材（由 ResearchAgent 产出，Map<String,String>：小节 -> 素材） */
        public static final String MATERIALS = "materials";

        /** 正文草稿（由 WriterAgent 产出，String，Markdown） */
        public static final String DRAFT = "draft";

        /** 评审意见（由 ReviewerAgent 产出，String） */
        public static final String REVIEW = "review";

        /** 评审分数（由 ReviewerAgent 产出，Double，0~1） */
        public static final String SCORE = "score";
    }

    /** 真正的黑板存储：线程安全的键值对 */
    private final Map<String, Object> board = new ConcurrentHashMap<>();

    /**
     * 写入一个数据项。
     *
     * @param key   键（务必使用 {@link Keys} 常量）
     * @param value 值（任意类型）
     */
    public void put(String key, Object value) {
        board.put(key, value);
        log.debug("[SharedMemory] put {} = {}", key,
                value instanceof String s && s.length() > 40 ? s.substring(0, 40) + "..." : value);
    }

    /**
     * 读一个数据项（带类型转换，读不到或类型不符返回 null）。
     *
     * @param key  键
     * @param type 期望类型
     * @param <T>  期望类型
     * @return 值；不存在或类型不符时返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Object v = board.get(key);
        if (v == null || !type.isInstance(v)) {
            return null;
        }
        return (T) v;
    }

    /**
     * 读字符串便捷方法。
     *
     * @param key 键
     * @return 字符串值，不存在返回 null
     */
    public String getString(String key) {
        return get(key, String.class);
    }

    /**
     * 判断是否已包含某键。
     *
     * @param key 键
     * @return 是否存在
     */
    public boolean contains(String key) {
        return board.containsKey(key);
    }

    /**
     * 导出整块黑板（只读快照），用于调试与日志展示。
     *
     * @return 黑板内容的浅拷贝
     */
    public Map<String, Object> dump() {
        return Map.copyOf(board);
    }
}