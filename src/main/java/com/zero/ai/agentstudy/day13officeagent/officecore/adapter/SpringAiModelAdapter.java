package com.zero.ai.agentstudy.day13officeagent.officecore.adapter;

import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.DocumentIR;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.port.ModelPort;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * Spring AI 大模型适配器——{@link ModelPort} 的出站适配器实现。
 *
 * <p><b>六边形架构中的位置：</b> 领域层只认识 {@link ModelPort} 这个抽象契约，完全不知道
 * Spring AI、{@code ChatClient}、OpenAI 的存在。本适配器把框架细节全部收拢在此，是领域与
 * 外部 LLM 之间唯一的翻译层。将来替换底层模型（换成本地部署、换成别家厂商），只需换掉
 * 这一个类，领域与 Pipeline 一行不用改——这正是依赖倒置（DIP）的价值。</p>
 *
 * <p><b>结构化输出是本适配器的灵魂能力：</b> 传统做法是让模型吐一段文本再手写正则/JSON 解析，
 * 既脆弱又冗长。Spring AI 2 的 {@code ChatClient.entity(Class)} 把这件事标准化了——框架自动
 * 在提示词里注入目标类型的 JSON Schema，引导模型产出结构化 JSON，再反序列化成目标对象。
 * 这里我们直接把目标类型指定为 {@link DocumentIR}，于是"读数据 → 分析 → 产出文档结构"这条链路
 * 被一次调用打通：模型返回的不是一段文字，而是一棵可校验、可渲染的文档语义树。</p>
 *
 * <p><b>为什么用构造器注入 {@code ChatClient.Builder} 而非 {@code ChatClient}？</b>
 * Builder 是原型级 Bean，每个适配器可以按需定制默认系统提示词、默认参数，互不干扰；
 * 直接注入构建好的 ChatClient 则共享同一份配置，灵活性更差。</p>
 *
 * @author zero
 */
@Component
public class SpringAiModelAdapter implements ModelPort {

    /**
     * 面向文档生成的系统提示词——约束模型"只产出结构化文档，不要寒暄，不要 Markdown 包裹"。
     *
     * <p>结构化输出虽由框架注入 Schema，但一段清晰的系统提示能显著提升字段填充质量，
     * 尤其能压制模型"在 JSON 外再补一段解释"的倾向。</p>
     */
    private static final String DOCUMENT_SYSTEM_PROMPT = """
            你是一名专业的企业文档撰写助手。请严格根据用户提供的指令与上下文数据，
            产出结构化的文档内容。要求：
            1. 只输出与文档结构对应的字段，不要输出任何寒暄、解释或额外说明；
            2. 文档应包含清晰的标题层级、段落，必要时用列表或表格组织数据；
            3. 语言精炼、专业、面向企业读者；
            4. 忠实于给定数据，不要编造上下文中不存在的数字或事实。
            """;

    private final ChatClient chatClient;

    /**
     * 构造适配器。
     *
     * @param builder Spring AI 提供的 ChatClient 构建器
     */
    public SpringAiModelAdapter(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @Override
    public String generateText(String prompt) {
        return chatClient.prompt()
                .user(prompt)
                .call()
                .content();
    }

    @Override
    public DocumentIR generateDocument(String instruction, String context) {
        String userMessage = """
                【生成指令】
                %s

                【上下文数据】
                %s
                """.formatted(
                instruction == null ? "" : instruction,
                context == null ? "（无）" : context);

        return chatClient.prompt()
                .system(DOCUMENT_SYSTEM_PROMPT)
                .user(userMessage)
                .call()
                .entity(DocumentIR.class);
    }

    @Override
    public <T> T generateStructured(String prompt, Class<T> targetType) {
        return chatClient.prompt()
                .user(prompt)
                .call()
                .entity(targetType);
    }
}