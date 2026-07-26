package com.zero.ai.agentstudy.day06workflow.workflow.context;

import com.zero.ai.agentstudy.day06workflow.workflow.core.WorkflowState;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * WorkflowContext —— 流程的「共享数据袋」。
 *
 * <p>教学要点：这是整个 Workflow 最关键的对象。
 * 各个 Node 之间不直接互相调用、不互相依赖，它们只通过 Context 交换数据：
 * 上游 Node 把结果 put 进 Context，下游 Node 再 get 出来用。
 * 这就是「数据驱动」而非「代码调用驱动」，也是 Workflow 可插拔的根本原因。</p>
 *
 * <p>设计对应：Day4 的 Memory 概念在这里表现为 Context 里的对话/中间数据；
 * 它同时承担「黑板模式(Blackboard)」的角色。</p>
 *
 * @author ZeroAi
 */
@Slf4j
@Getter
public class WorkflowContext {

    /** 本次运行的唯一 ID，用于日志追踪、断点恢复 */
    private final String runId;

    /** 共享数据区：所有节点读写的键值对。用 LinkedHashMap 保证插入顺序，便于调试观察 */
    private final Map<String, Object> data = new LinkedHashMap<>();

    /** 整条流程的宏观状态 */
    private WorkflowState state = WorkflowState.CREATED;

    /** 当前执行到第几个节点（从 0 开始），用于断点恢复 */
    private int currentIndex = 0;

    public WorkflowContext() {
        this.runId = UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 写入一个数据项。
     *
     * @param key   键，建议用常量集中管理避免拼写错误
     * @param value 值，任意类型
     */
    public void put(String key, Object value) {
        data.put(key, value);
        log.debug("[Context-{}] put {} = {}", runId, key, value);
    }

    /**
     * 读一个数据项（带类型转换）。
     *
     * @param key  键
     * @param type 期望类型的 Class 对象
     * @param <T>  期望类型
     * @return 值；不存在返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Object v = data.get(key);
        if (v == null) {
            return null;
        }
        if (!type.isInstance(v)) {
            throw new IllegalStateException(
                    "Context 中 key=" + key + " 的类型是 " + v.getClass().getSimpleName()
                            + "，无法转换为 " + type.getSimpleName());
        }
        return (T) v;
    }

    /** 便捷方法：读取字符串 */
    public String getString(String key) {
        return get(key, String.class);
    }

    /** 判断是否包含某个 key */
    public boolean contains(String key) {
        return data.containsKey(key);
    }

    /** 更新宏观状态（仅引擎调用） */
    public void setState(WorkflowState state) {
        log.info("[Context-{}] state {} -> {}", runId, this.state, state);
        this.state = state;
    }

    /** 移动到下一个节点（引擎调用） */
    public void advance() {
        this.currentIndex++;
    }
}