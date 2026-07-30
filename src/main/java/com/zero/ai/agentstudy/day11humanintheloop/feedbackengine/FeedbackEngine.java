package com.zero.ai.agentstudy.day11humanintheloop.feedbackengine;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 反馈引擎（Feedback Engine）——反馈收集与统计的编排入口（入站服务）。
 *
 * <p>职责边界：本类只负责「把人的反馈安全地收进来、存下去、给出基本统计」，
 * 不负责「怎么从反馈里学」。「学」的职责交给 {@link FeedbackLearningService}——
 * 这是典型的「收集」与「学习」职责分离：收集要快、要可靠、要幂等；学习可能耗时、
 * 可能异步、可能离线批处理。混在一起会让在线提交反馈的接口被学习逻辑拖慢。</p>
 *
 * <p>为什么需要一个专门的「收集入口」而不是让调用方直接写仓储？因为收集这一步有横切
 * 关注点：参数兜底、审计日志、去重、事件发布（反馈进来后可能要通知下游）。把这些收拢
 * 在一个入口，业务方只管调 {@code submitXxx}，无需关心背后细节。</p>
 */
public class FeedbackEngine {

    private final FeedbackRepository repository;

    public FeedbackEngine(FeedbackRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
    }

    // ---------------- 通用提交入口 ----------------

    /**
     * 提交一条已构造好的反馈（通用入口）。
     *
     * @param feedback 反馈对象
     * @return 落库后的反馈
     */
    public HumanFeedback submit(HumanFeedback feedback) {
        Objects.requireNonNull(feedback, "feedback 不能为空");
        // 此处是挂审计日志 / 发布领域事件的天然切点，教学期从简
        return repository.save(feedback);
    }

    // ---------------- 语义化提交入口（便于业务方直接调用） ----------------

    /**
     * 点赞：认可某次产出，可选评分。
     */
    public HumanFeedback approve(String taskId, String targetOutput, String reviewer, Integer score) {
        return submit(HumanFeedback.approve(taskId, targetOutput, reviewer, score));
    }

    /**
     * 点踩：否定某次产出，附一句原因。
     */
    public HumanFeedback reject(String taskId, String targetOutput, String reviewer, String reason) {
        return submit(HumanFeedback.reject(taskId, targetOutput, reviewer, reason));
    }

    /**
     * 纠正：给出期望的正确产出。
     */
    public HumanFeedback correct(String taskId, String targetOutput, String correctedOutput, String reviewer) {
        return submit(HumanFeedback.correct(taskId, targetOutput, correctedOutput, reviewer));
    }

    /**
     * 建议：给出改进方向。
     */
    public HumanFeedback suggest(String taskId, String targetOutput, String suggestion, String reviewer) {
        return submit(HumanFeedback.suggest(taskId, targetOutput, suggestion, reviewer));
    }

    // ---------------- 查询 ----------------

    /** 查某条反馈。 */
    public HumanFeedback get(String feedbackId) {
        return repository.findById(feedbackId);
    }

    /** 查某任务的全部反馈（时间升序）。 */
    public List<HumanFeedback> feedbackOf(String taskId) {
        return repository.findByTaskId(taskId);
    }

    /** 查某类反馈。 */
    public List<HumanFeedback> feedbackOfType(FeedbackType type) {
        return repository.findByType(type);
    }

    // ---------------- 基本统计（供报表 / 监控用） ----------------

    /**
     * 统计各反馈类型的数量分布。
     *
     * @return 类型 -> 条数（用 EnumMap 保证顺序稳定、访问高效）
     */
    public Map<FeedbackType, Long> typeDistribution() {
        java.util.EnumMap<FeedbackType, Long> dist = new java.util.EnumMap<>(FeedbackType.class);
        for (FeedbackType t : FeedbackType.values()) {
            dist.put(t, 0L);
       }
        for (HumanFeedback fb : repository.findAll()) {
            dist.merge(fb.type(), 1L, Long::sum);
        }
        return dist;
    }

    /**
     * 计算某任务的正向反馈占比（0.0~1.0），无反馈时返回 0。
     *
     * <p>这是衡量「Agent 在某任务上表现好不好」的最直观指标：正向占比越高越好。
     * 生产里常把它做成实时看板，一旦某任务正向率骤降就触发告警。</p>
     */
    public double positiveRatio(String taskId) {
        List<HumanFeedback> list = repository.findByTaskId(taskId);
        if (list.isEmpty()) {
            return 0.0;
        }
        long positive = list.stream().filter(HumanFeedback::isPositive).count();
        return (double) positive / list.size();
    }

    /**
     * 计算某任务的平均评分（仅统计有评分的反馈），无评分时返回 -1。
     */
    public double averageScore(String taskId) {
        List<HumanFeedback> list = repository.findByTaskId(taskId);
        double sum = 0;
        int cnt = 0;
        for (HumanFeedback fb : list) {
            if (fb.hasScore()) {
                sum += fb.score();
                cnt++;
            }
        }
        return cnt == 0 ? -1.0 : sum / cnt;
    }

    /** 反馈总量。 */
    public long total() {
        return repository.count();
    }
}