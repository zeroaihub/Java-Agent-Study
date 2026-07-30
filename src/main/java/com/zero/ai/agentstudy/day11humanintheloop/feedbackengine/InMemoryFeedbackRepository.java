package com.zero.ai.agentstudy.day11humanintheloop.feedbackengine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版反馈仓储（教学 / 单测用）。
 *
 * <p>用 {@link ConcurrentHashMap} 承载，保证多线程提交反馈时的线程安全。所有查询结果
 * 都是「新列表快照」，避免调用方拿到内部集合的引用后误改内部状态——这是仓储层做防御性
 * 返回的基本素养。</p>
 *
 * <p>生产替换指引：把本类换成 {@code JdbcFeedbackRepository} 或 {@code VectorFeedbackRepository}
 * 即可，上层 {@link FeedbackEngine} / {@link FeedbackLearningService} 完全无感知。</p>
 */
public class InMemoryFeedbackRepository implements FeedbackRepository {

    /** key = feedbackId。 */
    private final Map<String, HumanFeedback> store = new ConcurrentHashMap<>();

    @Override
    public HumanFeedback save(HumanFeedback feedback) {
        Objects.requireNonNull(feedback, "feedback 不能为空");
        store.put(feedback.feedbackId(), feedback);
        return feedback;
    }

    @Override
    public HumanFeedback findById(String feedbackId) {
        if (feedbackId == null) {
            return null;
        }
        return store.get(feedbackId);
    }

    @Override
    public List<HumanFeedback> findByTaskId(String taskId) {
        List<HumanFeedback> result = new ArrayList<>();
        for (HumanFeedback fb : store.values()) {
            if (Objects.equals(fb.taskId(), taskId)) {
                result.add(fb);
            }
        }
        result.sort(Comparator.comparing(HumanFeedback::createdAt));
        return result;
    }

    @Override
    public List<HumanFeedback> findByType(FeedbackType type) {
        List<HumanFeedback> result = new ArrayList<>();
        for (HumanFeedback fb : store.values()) {
            if (fb.type() == type) {
                result.add(fb);
            }
        }
        result.sort(Comparator.comparing(HumanFeedback::createdAt));
        return result;
    }

    @Override
    public List<HumanFeedback> findAll() {
        List<HumanFeedback> result = new ArrayList<>(store.values());
        result.sort(Comparator.comparing(HumanFeedback::createdAt));
        return result;
    }

    @Override
    public long count() {
        return store.size();
    }
}