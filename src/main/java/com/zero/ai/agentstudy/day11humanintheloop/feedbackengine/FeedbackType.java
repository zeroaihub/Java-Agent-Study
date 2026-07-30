package com.zero.ai.agentstudy.day11humanintheloop.feedbackengine;

/**
 * 人工反馈类型（Feedback Type）。
 *
 * <p>在 HITL（Human-in-the-loop）里，人不仅仅是「审批通过 / 拒绝」这么简单的二值开关，
 * 更多时候，人对 Agent 的产出会给出「有信息量」的反馈：这条回答不错（点赞）、这条太差了
 * （点踩）、这里应该这样改（纠正）、下次可以试试这个思路（建议）。这些反馈如果只是记录一下
 * 就丢掉，那就浪费了；如果能沉淀成「经验」反哺给 Agent，Agent 就能越用越聪明——这就是
 * 「反馈学习（Feedback Learning）」的价值。</p>
 *
 * <p>本枚举把人工反馈归为四种典型类型，覆盖了绝大多数生产场景。用枚举而非字符串，是为了
 * 让「反馈类型」成为一个受控的、可穷举的、编译期可校验的集合——避免上游随手传个
 * {@code "good"}、{@code "GOOD"}、{@code "点赞"} 导致下游统计口径混乱。</p>
 *
 * <p>设计取舍：为什么不做成可扩展的接口？因为反馈类型是「业务语义高度稳定」的领域概念，
 * 十年内基本不会变，而枚举带来的 switch 穷尽性检查、序列化友好、EnumMap 高性能，
 * 都是接口方案换不来的。若确有扩展需求，再用 {@code metadata} 承载即可。</p>
 */
public enum FeedbackType {

    /**
     * 认可 / 点赞：人对 Agent 的产出表示满意。
     * <p>典型用途：把「被点赞的输入-输出对」收集起来，作为 few-shot 正例，强化好的行为。</p>
     */
    APPROVE_RATING("认可", true),

    /**
     * 否定 / 点踩：人对 Agent 的产出表示不满意，但未必给出如何改。
     * <p>典型用途：把「被点踩的样本」标记为负例，用于告警、回流人工复核、或训练时降权。</p>
     */
    REJECT_RATING("否定", false),

    /**
     * 纠正（Correction）：人不仅否定，还直接给出了「正确答案 / 期望产出」。
     * <p>这是最有价值的一类反馈——它自带 ground truth。典型用途：直接沉淀为 few-shot 示例
     * （输入 + 人给的正确输出），下次遇到相似输入就能引用。</p>
     */
    CORRECTION("纠正", false),

    /**
     * 建议（Suggestion）：人给出改进方向 / 提示，但不一定是完整的正确答案。
     * <p>典型用途：沉淀为「软知识 / 提示词补充」，例如「回答金融问题时要加免责声明」。</p>
     */
    SUGGESTION("建议", true);

    /** 人类可读标签（用于日志、审计、UI 展示）。 */
    private final String label;

    /**
     * 是否为「正向反馈」。
     * <p>APPROVE_RATING / SUGGESTION 视为正向（可作正例强化），
     * REJECT_RATING / CORRECTION 视为负向（表示当前产出不达标）。
     * 注意：CORRECTION 虽是负向，但它「纠正后的内容」是极好的正例来源。</p>
     */
    private final boolean positive;

    FeedbackType(String label, boolean positive) {
        this.label = label;
        this.positive = positive;
    }

    public String label() {
        return label;
    }

    public boolean isPositive() {
        return positive;
    }

    /**
     * 该反馈类型是否「携带可学习的正确内容」。
     * <p>只有 CORRECTION 会带来明确的 ground truth（人给的正确输出），
     * 是 Feedback Learning 沉淀 few-shot 正例的首选来源。</p>
     */
    public boolean carriesGroundTruth() {
        return this == CORRECTION;
    }
}