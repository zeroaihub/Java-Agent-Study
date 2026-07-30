package com.zero.ai.agentstudy.day11humanintheloop.feedbackengine;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 人工反馈（Human Feedback）——一条「人对 Agent 某次产出」的评价记录。
 *
 * <p>这是 Feedback Engine 的核心领域对象。它把「谁、在什么时候、针对哪次产出、给了什么样的
 * 反馈、内容是什么、打了几分」这一整件事，凝固成一个不可变的记录。之所以用 {@code record}，
 * 是因为反馈一旦提交就代表一个「已发生的事实」，不该被事后篡改——审计、复盘、离线训练时，
 * 我们要的正是「当时人到底说了什么」，而不是「后来被改成了什么」。</p>
 *
 * <p>字段设计说明：</p>
 * <ul>
 *   <li>{@code feedbackId}：反馈自身的唯一 ID，便于去重、追溯、引用。</li>
 *   <li>{@code taskId}：这条反馈归属的 Agent 任务（对应 {@code AgentAction.taskId}），
 *       把「人的评价」与「机器的动作」串起来。</li>
 *   <li>{@code targetOutput}：被评价的那段 Agent 产出（原始输出快照）。学习时它就是「输入侧」。</li>
 *   <li>{@code type}：反馈类型（点赞/点踩/纠正/建议），决定后续如何被学习消费。</li>
 *   <li>{@code content}：人写的具体反馈文字。对 CORRECTION 而言，这里放的就是「正确答案」。</li>
 *   <li>{@code score}：可选评分（如 1~5 星）。null 表示未评分。</li>
 *   <li>{@code reviewer}：反馈提交人（审计必备）。</li>
 *   <li>{@code metadata}：扩展位（只读快照），承载业务自定义字段，保持模型开放。</li>
 *   <li>{@code createdAt}：反馈发生时间。</li>
 * </ul>
 *
 * @param feedbackId反馈唯一 ID
 * @param taskId       所属 Agent 任务 ID
 * @param targetOutput 被评价的 Agent 产出（原始快照）
 * @param type         反馈类型
 * @param content      反馈内容（CORRECTION 时即为期望的正确产出）
 * @param score        评分（可为 null）
 * @param reviewer     反馈人
 * @param metadata     扩展元数据（只读）
 * @param createdAt    创建时间
 */
public record HumanFeedback(
        String feedbackId,
        String taskId,
        String targetOutput,
        FeedbackType type,
        String content,
        Integer score,
        String reviewer,
        Map<String, Object> metadata,
        Instant createdAt
) {

    /** 评分下限（含）。 */
    public static final int MIN_SCORE = 1;
    /** 评分上限（含）。 */
    public static final int MAX_SCORE = 5;

    /**
     * 紧凑构造器：非空校验 + 评分区间校验 + 元数据防御性拷贝。
     */
    public HumanFeedback {
        Objects.requireNonNull(feedbackId, "feedbackId 不能为空");
        Objects.requireNonNull(taskId, "taskId 不能为空");
        Objects.requireNonNull(targetOutput, "targetOutput 不能为空");
        Objects.requireNonNull(type, "type 不能为空");
        Objects.requireNonNull(reviewer, "reviewer 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        if (score != null && (score < MIN_SCORE || score > MAX_SCORE)) {
            throw new IllegalArgumentException(
                    "score 必须在 [" + MIN_SCORE + ", " + MAX_SCORE + "] 之间，实际：" + score);
        }
        // CORRECTION 必须带出「正确内容」，否则这条纠正没有可学习的价值
        if (type == FeedbackType.CORRECTION && (content == null || content.isBlank())) {
            throw new IllegalArgumentException("CORRECTION 类型的反馈必须提供 content（期望的正确产出）");
        }
        metadata = (metadata == null) ? Map.of() : Map.copyOf(metadata);
    }

    // ---------------- 便捷工厂 ----------------

    /**
     * 点赞：认可某次产出。
     */
    public static HumanFeedback approve(String taskId, String targetOutput, String reviewer, Integer score) {
        return new HumanFeedback(newId(), taskId, targetOutput,
                FeedbackType.APPROVE_RATING, null, score, reviewer, Map.of(), Instant.now());
    }

    /**
     * 点踩：否定某次产出（可附一句原因）。
     */
    public static HumanFeedback reject(String taskId, String targetOutput, String reviewer, String reason) {
        return new HumanFeedback(newId(), taskId, targetOutput,
                FeedbackType.REJECT_RATING, reason, null, reviewer, Map.of(), Instant.now());
    }

    /**
     * 纠正：给出期望的正确产出（correctedOutput 即 ground truth）。
     */
    public static HumanFeedback correct(String taskId, String targetOutput, String correctedOutput, String reviewer) {
        return new HumanFeedback(newId(), taskId, targetOutput,
                FeedbackType.CORRECTION, correctedOutput, null, reviewer, Map.of(), Instant.now());
    }

    /**
     * 建议：给出改进方向。
     */
    public static HumanFeedback suggest(String taskId, String targetOutput, String suggestion, String reviewer) {
        return new HumanFeedback(newId(), taskId, targetOutput,
                FeedbackType.SUGGESTION, suggestion, null, reviewer, Map.of(), Instant.now());
    }

    // ---------------- 行为方法 ----------------

    /** 是否为正向反馈（点赞 / 建议）。 */
    public boolean isPositive() {
        return type.isPositive();
    }

    /** 是否携带可直接学习的正确内容（仅 CORRECTION）。 */
    public boolean carriesGroundTruth() {
        return type.carriesGroundTruth();
    }

    /** 是否已评分。 */
    public boolean hasScore() {
        return score != null;
    }

    /**
     * 读取「可学习的正确产出」：
     * <ul>
     *   <li>CORRECTION：返回人给的 content（正确答案）；</li>
     *   <li>其他类型：没有明确的正确答案，返回 null。</li>
     * </ul>
     */
    public String learnableOutput() {
        return carriesGroundTruth() ? content : null;
    }

    private static String newId() {
        return "fb-" + UUID.randomUUID().toString().substring(0, 8);
    }
}