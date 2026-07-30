package com.zero.ai.agentstudy.day12longrunningagent.session;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 运行上下文（Context）。
 *
 * <p>承载一个长任务在执行过程中的"进度指针"与"中间产物"。它是 Checkpoint 打点与
 * Recovery 恢复的核心载体：崩溃后从 Store 读回 Context，即可从上次的 stepIndex 续跑。</p>
 *
 * <p>设计原则：Context 只存"可序列化的、恢复所必需的"数据，不存活跃连接、线程等运行时对象。</p>
 */
public class AgentContext {

    /** 当前执行到第几步（0 基）。Recovery 从此指针续跑。 */
    private int stepIndex;

    /** 步骤间传递的中间产物（KV）。例如上一步抓取的 HTML、上一步生成的摘要。 */
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    /** 本轮已重试次数（用于重试上限判断）。 */
    private int retryCount;

    public int getStepIndex() {
        return stepIndex;
    }

    public void setStepIndex(int stepIndex) {
        this.stepIndex = stepIndex;
    }

    /** 推进到下一步，返回新的 stepIndex。 */
    public int advance() {
        return ++this.stepIndex;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public int incrementRetry() {
        return ++this.retryCount;
    }

    public void resetRetry() {
        this.retryCount = 0;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void put(String key, Object value) {
        attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) attributes.get(key);
    }

    public boolean has(String key) {
        return attributes.containsKey(key);
    }

    @Override
    public String toString() {
        return "AgentContext{stepIndex=" + stepIndex
                + ", retryCount=" + retryCount
                + ", attributes=" + attributes.keySet() + "}";
    }
}