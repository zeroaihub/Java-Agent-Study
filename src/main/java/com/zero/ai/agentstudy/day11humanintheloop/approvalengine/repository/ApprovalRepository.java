package com.zero.ai.agentstudy.day11humanintheloop.approvalengine.repository;

import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalRequest;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalStatus;

import java.util.List;
import java.util.Optional;

/**
 * 审批仓储（Repository 抽象）。
 *
 * <p>把「审批请求存哪、怎么存、怎么查」这件事抽象成接口，是为了让审批引擎
 * 的核心逻辑与具体存储解耦。教学阶段用内存实现（{@link InMemoryApprovalRepository}），
 * 生产可无缝替换为 Redis（快、支持 TTL 超时）+ PostgreSQL（稳、可审计）的组合实现，
 * 而审批引擎一行代码都不用改。</p>
 *
 * <p>这是六边形架构里典型的「出站端口（Outbound Port）」。</p>
 */
public interface ApprovalRepository {

    /**
     * 保存或更新一个审批请求（按 requestId 幂等覆盖）。
     */
    void save(ApprovalRequest request);

    /**
     * 按 ID 查找审批请求。
     */
    Optional<ApprovalRequest> findById(String requestId);

    /**
     * 查询所有处于指定状态的请求（例如捞出所有 PENDING 用于超时扫描）。
     */
    List<ApprovalRequest> findByStatus(ApprovalStatus status);

    /**
     * 查询某个任务下的所有审批请求。
     */
    List<ApprovalRequest> findByTaskId(String taskId);

    /**
     * 删除一个审批请求（一般不用，审批记录通常保留用于审计）。
     */
    void deleteById(String requestId);

    /**
     * 统计当前仓储中的请求总数（便于测试与监控）。
     */
    long count();
}