package com.zero.ai.agentstudy.day11humanintheloop.feedbackengine;

import java.util.List;

/**
 * 反馈仓储（Feedback Repository）——出站端口（Outbound Port）。
 *
 * <p>遵循六边形架构 + 依赖倒置（DIP）：Feedback Engine 只依赖这个接口，不关心底层是
 * 内存、PostgreSQL 还是向量库。教学期我们用内存实现，生产可无缝替换为 JDBC / JPA /
 * 向量检索实现，业务代码零改动。</p>
 *
 * <p>为什么反馈要单独持久化，而不塞进 ApprovalRequest？因为反馈的生命周期与查询维度
 * 和审批完全不同：审批是「一次性决策」，反馈是「持续积累的语料」，会被离线训练、
 * 相似检索、统计报表反复消费。职责不同，存储自然分离。</p>
 */
public interface FeedbackRepository {

    /**
     * 保存一条反馈（同 ID 覆盖，天然幂等）。
     *
     * @param feedback 待保存反馈
     * @return 保存后的反馈
     */
    HumanFeedback save(HumanFeedback feedback);

    /**
     * 按反馈 ID 查询。
     *
     * @param feedbackId 反馈 ID
     * @return 命中则返回，否则 null
     */
    HumanFeedback findById(String feedbackId);

    /**
     * 按任务 ID 查询该任务收到的全部反馈（按时间升序）。
     *
     * @param taskId 任务 ID
     * @return 反馈列表（可能为空，不为 null）
     */
    List<HumanFeedback> findByTaskId(String taskId);

    /**
     * 按反馈类型查询（用于分类统计 / 抽取某类反馈做学习）。
     *
     * @param type 反馈类型
     * @return 反馈列表（可能为空，不为 null）
     */
    List<HumanFeedback> findByType(FeedbackType type);

    /**
     * 返回全部反馈（按时间升序）。
     *
     * @return 反馈列表（可能为空，不为 null）
     */
    List<HumanFeedback> findAll();

    /**
     * 反馈总条数。
     *
     * @return 数量
     */
    long count();
}