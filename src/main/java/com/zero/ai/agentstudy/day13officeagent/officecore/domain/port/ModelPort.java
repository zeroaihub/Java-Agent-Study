package com.zero.ai.agentstudy.day13officeagent.officecore.domain.port;

import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.DocumentIR;

/**
 * 大模型端口（ModelPort）——出站端口，领域与 LLM 之间的唯一契约。
 *
 * <p>生成阶段需要调用大模型，但领域不应直接依赖 Spring AI 的 {@code ChatClient}——那会把
 * 框架细节泄漏进核心。ModelPort 把领域真正需要的两类能力抽象出来：普通文本生成，以及
 * <b>结构化输出</b>——直接把模型响应解析成与格式无关的 {@link DocumentIR}。适配器
 * （{@code SpringAiModelAdapter}）用 {@code ChatClient.entity(DocumentIR.class)} 实现它。</p>
 *
 * <p>依赖倒置的价值在此凸显：将来把底层模型从 OpenAI 换成本地部署，或在测试中注入
 * 一个确定性的假实现，领域与 Pipeline 代码<b>一行不用改</b>。</p>
 *
 * @author zero
 */
public interface ModelPort {

    /**
     * 生成纯文本。
     *
     * @param prompt 提示词
     * @return 模型文本响应
     */
    String generateText(String prompt);

    /**
     * 结构化生成文档 IR——把模型输出直接映射为文档中间表示。
     *
     * @param instruction 面向文档生成的指令
     * @param context     补充上下文（如结构化的销售数据摘要）
     * @return 生成的文档 IR
     */
    DocumentIR generateDocument(String instruction, String context);

    /**
     * 通用结构化输出——把模型响应解析为任意目标类型。
     *
     * @param prompt      提示词
     * @param targetType  目标类型
     * @param <T>         类型参数
     * @return 解析后的对象
     */
    <T> T generateStructured(String prompt, Class<T> targetType);
}