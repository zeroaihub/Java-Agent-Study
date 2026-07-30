package com.zero.ai.agentstudy.day13officeagent.officecore.domain.context;

import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.DocumentFormat;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.DocumentIR;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 办公工作流上下文（OfficeContext）——贯穿七阶段 Pipeline 的共享"工作台"。
 *
 * <p>责任链上的每个阶段处理器（Handler）都读写同一个 OfficeContext：感知阶段写入原始输入，
 * 生成阶段写入 {@link DocumentIR}，渲染阶段写入各格式产物字节，交付阶段读取产物去分发。
 * 由于上下文把"任务进行到哪一步、已经产出了什么"完整记录下来，它天然支持<b>断点续跑</b>——
 * 只要能把 OfficeContext 序列化持久化，任务中断后即可从上次的 {@link #currentStage} 恢复。</p>
 *
 * <p>本类是可变聚合，但通过内部并发容器保证多阶段/多线程写入安全，适配 Virtual Threads 与
 * 结构化并发下的多格式并行渲染。它<b>不</b>关心任何具体格式引擎，只承载数据与状态。</p>
 *
 * @author zero
 */
public final class OfficeContext {

    private final String taskId;
    private final TenantContext tenant;
    private final Instant startedAt;

    private volatile PipelineStage currentStage;
    private volatile DocumentIR documentIR;

    /** 各阶段任意共享属性（如原始 Excel 数据、规划结果、审批意见）。 */
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    /** 各目标格式的渲染产物字节。 */
    private final Map<DocumentFormat, byte[]> renderedArtifacts = new ConcurrentHashMap<>();

    /** 各阶段完成时间戳，用于观测与耗时分析。 */
    private final Map<PipelineStage, Instant> stageCompletedAt =
            new EnumMap<>(PipelineStage.class);

    private OfficeContext(String taskId, TenantContext tenant) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        if (tenant == null) {
            throw new IllegalArgumentException("tenant 不能为空");
        }
        this.taskId = taskId;
        this.tenant = tenant;
        this.startedAt = Instant.now();
        this.currentStage = PipelineStage.PERCEIVE;
    }

    /**
     * 创建一个新的工作流上下文，起始阶段为感知。
     *
     * @param taskId 任务标识
     * @param tenant 租户上下文
     * @return 新的 OfficeContext
     */
    public static OfficeContext create(String taskId, TenantContext tenant) {
        return new OfficeContext(taskId, tenant);
    }

    /** 任务唯一标识。 */
    public String taskId() {
        return taskId;
    }

    /** 租户上下文。 */
    public TenantContext tenant() {
        return tenant;
    }

    /** 任务开始时间。 */
    public Instant startedAt() {
        return startedAt;
    }

    /** 当前所处阶段。 */
    public PipelineStage currentStage() {
        return currentStage;
    }

    /**
     * 推进到下一阶段，并记录上一阶段完成时间。
     *
     * @param next 下一阶段
     */
    public void advanceTo(PipelineStage next) {
        this.stageCompletedAt.put(this.currentStage, Instant.now());
        this.currentStage = next;
    }

    /** 当前的文档 IR（可能尚未生成）。 */
    public Optional<DocumentIR> documentIR() {
        return Optional.ofNullable(documentIR);
    }

    /** 设置生成阶段产出的文档 IR。 */
    public void setDocumentIR(DocumentIR ir) {
        this.documentIR = ir;
    }

    /**
     * 写入一个阶段间共享属性。
     *
     * @param key   属性键
     * @param value 属性值
     */
    public void put(String key, Object value) {
        if (key != null && value != null) {
            attributes.put(key, value);
        }
    }

    /**
     * 读取共享属性并按目标类型转换。
     *
     * @param key  属性键
     * @param type 期望类型
     * @param <T>  类型参数
     * @return 属性值（若不存在或类型不符则为空）
     */
    public <T> Optional<T> get(String key, Class<T> type) {
        Object v = attributes.get(key);
        if (type.isInstance(v)) {
            return Optional.of(type.cast(v));
        }
        return Optional.empty();
    }

    /**
     * 记录某格式的渲染产物。
     *
     * @param format 目标格式
     * @param bytes  产物字节
     */
    public void addArtifact(DocumentFormat format, byte[] bytes) {
        if (format != null && bytes != null) {
            renderedArtifacts.put(format, bytes.clone());
        }
    }

    /** 获取指定格式的渲染产物。 */
    public Optional<byte[]> artifact(DocumentFormat format) {
        byte[] b = renderedArtifacts.get(format);
        return b == null ? Optional.empty() : Optional.of(b.clone());
    }

    /** 已渲染的格式集合视图。 */
    public Map<DocumentFormat, byte[]> artifacts() {
        return Map.copyOf(renderedArtifacts);
    }
}