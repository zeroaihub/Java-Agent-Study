package com.zero.ai.agentstudy.day17codeagent.generator;

/**
 * 代码模型端口。
 *
 * <p>把「调用哪个模型」从业务逻辑里彻底剥出去。整个 Day17 <b>只有这一个接口</b>
 * 知道 LLM 的存在，其余模块一律依赖它。于是：</p>
 * <ul>
 *     <li>教程 Demo 用 {@code StubCodeModel} —— 零依赖、可离线、输出确定；</li>
 *     <li>生产用 {@code SpringAiCodeModel} —— 换成 Spring AI 的 ChatClient；</li>
 *     <li>评测时用 {@code RecordingCodeModel} —— 录下每次调用便于回放。</li>
 * </ul>
 *
 * <p><b>为什么不做 String-only 的返回</b>：{@link GenerationOutcome} 里必须带 token 消耗，
 * 否则成本与配额（Chapter 19）无从谈起——而「Agent 跑一夜烧掉两万块」是真实事故。</p>
 *
 * @author ZeroAi
 */
public interface CodeModel {

    /**
     * 生成一次代码。
     *
     * @param request 生成请求（含完整提示词）
     * @return 生成结果，含输出文本与用量统计
     */
    GenerationOutcome generate(GenerationRequest request);

    /** 模型标识，用于追踪与评测（同一批结果必须标注同一模型）。 */
    String modelId();
}
