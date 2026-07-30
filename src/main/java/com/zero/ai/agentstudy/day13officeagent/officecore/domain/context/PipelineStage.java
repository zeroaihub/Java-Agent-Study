package com.zero.ai.agentstudy.day13officeagent.officecore.domain.context;

/**
 * 文档流水线阶段（PipelineStage）枚举——Office Agent 七阶段处理模型。
 *
 * <p>一次"根据数据生成周报并制作 PPT 发送"的复杂任务，被拆解为七个职责单一、可独立测试、
 * 可断点续跑的阶段，由责任链依次驱动，状态机守护合法流转：</p>
 *
 * <ol>
 *   <li>{@link #PERCEIVE} 感知：读取输入（Excel、邮件、扫描件 OCR），归一为结构化数据。</li>
 *   <li>{@link #PLAN} 规划：由 Agent 决定要产出哪些文档、走哪些工具、是否需要人工审批。</li>
 *   <li>{@link #GENERATE} 生成：调用大模型结构化输出，产出与格式无关的 {@code DocumentIR}。</li>
 *   <li>{@link #RENDER} 渲染：多格式并行渲染，把 IR 翻译成 Word/Excel/PPT/PDF 二进制。</li>
 *   <li>{@link #REVIEW} 审批：Human-in-the-loop，关键交付前挂起等待人工确认。</li>
 *   <li>{@link #DELIVER} 交付：发邮件、上传知识库、写文件存储、Browser 联动上传。</li>
 *   <li>{@link #OBSERVE} 观测：记录指标与链路，沉淀可观测性数据。</li>
 * </ol>
 *
 * @author zero
 */
public enum PipelineStage {

    /** 感知：输入采集与结构化。 */
    PERCEIVE(1, "感知"),
    /** 规划：任务拆解与工具编排。 */
    PLAN(2, "规划"),
    /** 生成：产出与格式无关的文档 IR。 */
    GENERATE(3, "生成"),
    /** 渲染：IR 到多格式二进制。 */
    RENDER(4, "渲染"),
    /** 审批：人工确认（可选阶段）。 */
    REVIEW(5, "审批"),
    /** 交付：分发到邮件/知识库/存储。 */
    DELIVER(6, "交付"),
    /** 观测：指标与链路记录。 */
    OBSERVE(7, "观测");

    private final int order;
    private final String label;

    PipelineStage(int order, String label) {
        this.order = order;
        this.label = label;
    }

    /** 阶段顺序号（1~7）。 */
    public int order() {
        return order;
    }

    /** 中文标签，便于日志与可视化。 */
    public String label() {
        return label;
    }
}