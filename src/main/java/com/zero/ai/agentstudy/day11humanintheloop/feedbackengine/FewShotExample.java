package com.zero.ai.agentstudy.day11humanintheloop.feedbackengine;

import java.time.Instant;
import java.util.Objects;

/**
 * Few-shot 示例（学习产物 / Learned Example）。
 *
 * <p>这是「反馈学习」最终沉淀下来的、可以直接反哺给 Agent 的知识单元。它把一条有价值的
 * 人工反馈，提炼成「输入侧 + 期望输出侧」的一对样例，将来拼进 Prompt 的 few-shot 区域，
 * 就能引导大模型「照着人教过的正确样子」去回答相似问题——这就是最轻量、最实用、
 * 无需重新训练模型的「在线学习」方式（也叫 In-Context Learning）。</p>
 *
 * <p>为什么是「不可变 record」？因为一条 few-shot 示例代表「一次已被验证过的正确经验」，
 * 它应当稳定、可追溯、可版本化。如果谁都能随手改它，Prompt 里注入的示例就不再可信了。</p>
 *
 * <p>与 HumanFeedback 的关系：HumanFeedback 是「原始素材」（人当时说了啥），
 * FewShotExample 是「提炼产物」（从素材里抽出的可复用经验）。一条 CORRECTION 反馈
 * 通常能直接提炼出一条高质量 FewShotExample。</p>
 *
 * @param exampleId     示例唯一 ID
 * @param taskType      适用的任务类型（可对应 AgentAction.type，用于按场景检索匹配的示例）
 * @param input         输入侧：被评价的原始产出对应的「问题 / 场景」描述
 * @param expectedOutput 期望输出侧：人认可或纠正后的正确产出（这是要教给模型的「标准答案」）
 * @param sourceFeedbackId 来源反馈 ID（可追溯到原始反馈，便于审计与撤回）
 * @param weight        权重（示例被采纳时的置信度 / 重要度，越高越优先注入 Prompt）
 * @param createdAt     生成时间
 */
public record FewShotExample(
        String exampleId,
        String taskType,
        String input,
        String expectedOutput,
        String sourceFeedbackId,
        double weight,
        Instant createdAt
) {

    /** 默认权重。 */
    public static final double DEFAULT_WEIGHT = 1.0;

    public FewShotExample {
        Objects.requireNonNull(exampleId, "exampleId 不能为空");
        Objects.requireNonNull(taskType, "taskType 不能为空");
        Objects.requireNonNull(input, "input 不能为空");
        Objects.requireNonNull(expectedOutput, "expectedOutput 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        if (weight < 0) {
            throw new IllegalArgumentException("weight 不能为负：" + weight);
        }
    }

    /**
     * 便捷工厂：从一条 CORRECTION 反馈提炼一条 few-shot 示例。
     *
   * @param taskType 任务类型
     * @param feedback 来源反馈（应为 CORRECTION 类型，携带正确产出）
     * @return few-shot 示例
     * @throws IllegalArgumentException 若反馈不携带可学习的正确内容
     */
    public static FewShotExample fromCorrection(String taskType, HumanFeedback feedback) {
        Objects.requireNonNull(feedback, "feedback 不能为空");
        String learnable = feedback.learnableOutput();
        if (learnable == null) {
            throw new IllegalArgumentException(
                    "反馈 [" + feedback.feedbackId() + "] 不携带可学习的正确产出，无法提炼 few-shot 示例");
        }
        return new FewShotExample(
                "ex-" + feedback.feedbackId(),
                taskType,
                feedback.targetOutput(),
                learnable,
                feedback.feedbackId(),
                DEFAULT_WEIGHT,
                Instant.now());
    }

    /**
     * 渲染成可直接拼进 Prompt 的文本片段。
     *
     * <p>格式采用最通用的「示例块」写法，实际项目可按所用模型的最佳实践调整模板。</p>
     */
    public String toPromptSnippet() {
        return """
                【示例】
                场景/输入：%s
                正确产出：%s
                """.formatted(input, expectedOutput);
    }
}