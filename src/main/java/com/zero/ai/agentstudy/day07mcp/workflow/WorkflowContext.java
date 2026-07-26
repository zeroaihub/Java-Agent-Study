package com.zero.ai.agentstudy.day07mcp.workflow;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WorkflowContext —— 工作流上下文（节点之间传递数据的「黑板」）。
 *
 * <p>教学要点：一条工作流由多个节点串起来，前一个节点的产出往往是后一个节点的输入。
 * 我们用一个共享的「黑板（Blackboard）」在节点间传值，而不是让节点互相直接持有引用——
 * 这让节点彼此解耦：节点只认识 key，不认识别的节点。</p>
 *
 * <p>例如「查天气」节点把结果写进 {@code weatherText}，「穿衣建议」节点再从
 * {@code weatherText} 读出来做二次加工。两个节点谁也不认识谁，只通过黑板协作。</p>
 *
 * @author ZeroAi
 */
public class WorkflowContext {

    /** 初始用户输入（如城市名） */
    private final Map<String, Object> input = new LinkedHashMap<>();

    /** 各节点产出的中间/最终数据，key 由节点约定 */
    private final Map<String, Object> data = new LinkedHashMap<>();

    /**
     * 写入一条初始输入。
     *
     * @param key   键
     * @param value 值
     * @return this，便于链式调用
     */
    public WorkflowContext withInput(String key, Object value) {
        input.put(key, value);
        return this;
    }

    /**
     * 读取初始输入。
     *
     * @param key 键
     * @return 值，可能为 null
     */
    public Object getInput(String key) {
        return input.get(key);
    }

    /**
     * 节点写入产出数据。
     *
     * @param key   键
     * @param value 值
     */
    public void put(String key, Object value) {
        data.put(key, value);
    }

    /**
     * 读取某节点写入的产出数据。
     *
     * @param key 键
     * @return 值，可能为 null
     */
    public Object get(String key) {
        return data.get(key);
    }

    /**
     * 读取产出数据并转成字符串（null 安全）。
     *
     * @param key 键
     * @return 字符串，缺省为空串
     */
    public String getString(String key) {
        Object v = data.get(key);
        return v == null ? "" : String.valueOf(v);
    }



    /**
     * 只读快照，便于打印整条流程的最终产出。
     *
     * @return 数据副本
     */
    public Map<String, Object> snapshot() {
        return new LinkedHashMap<>(data);
    }
}