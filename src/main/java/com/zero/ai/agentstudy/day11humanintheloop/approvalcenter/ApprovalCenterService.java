package com.zero.ai.agentstudy.day11humanintheloop.approvalcenter;

import com.zero.ai.agentstudy.day11humanintheloop.approvalengine.repository.ApprovalRepository;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalRequest;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalStatus;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 企业审批中心服务——纯读侧聚合，把散落在仓储里的审批请求汇总成一块运营仪表盘。
 *
 * <p><b>只读、不改状态：</b>本服务只调用仓储的查询方法，绝不触发任何审批流转。这是
 * CQRS 读侧的定位——它可以随意重算、缓存、并发访问，因为它对写模型毫无副作用。</p>
 *
 * <p><b>不改内核：</b>{@link ApprovalRepository} 接口没有「查全部」的方法（生产实现里
 * 全表扫描是危险操作，不应轻易暴露）。这里通过遍历所有 {@link ApprovalStatus} 分别
 * {@code findByStatus} 再合并的方式拿到全量视图——既复用了既有接口，又把「全表扫描」
 * 的语义显式化。教学内存实现下这完全够用；生产可替换为一个带分页/时间窗过滤的专用查询。</p>
 */
@Service
public class ApprovalCenterService {

    /** 待办摘要在首页最多展示的条数，避免请求量大时把响应撑爆。 */
    private static final int MAX_RECENT_PENDING = 20;

    private final ApprovalRepository approvalRepository;

    public ApprovalCenterService(ApprovalRepository approvalRepository) {
        this.approvalRepository = approvalRepository;
    }

    /**
     * 生成一份实时审批仪表盘快照。
     *
     * @return 全模块审批状态的聚合视图
     */
    public ApprovalDashboard buildDashboard() {
        List<ApprovalRequest> all = loadAll();

        // 按状态名分组计数（先给每个已知状态预置 0，保证前端拿到完整的状态维度）。
        Map<String, Long> statusBreakdown = new java.util.LinkedHashMap<>();
        for (ApprovalStatus s : ApprovalStatus.values()) {
            statusBreakdown.put(s.name(), 0L);
        }
        for (ApprovalRequest r : all) {
            statusBreakdown.merge(r.getStatus().name(), 1L, Long::sum);
        }

        // 按风险等级分组计数。
        Map<String, Long> riskBreakdown = all.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getRiskLevel().name(),
                        Collectors.counting()));

        long terminalCount = all.stream()
                .filter(r -> r.getStatus().isTerminal())
                .count();

        List<ApprovalDashboard.PendingSummary> recentPending = all.stream()
                .filter(r -> r.getStatus() == ApprovalStatus.PENDING)
                .sorted(Comparator.comparing(ApprovalRequest::getCreatedAt).reversed())
                .limit(MAX_RECENT_PENDING)
                .map(this::toPendingSummary)
                .toList();

        return new ApprovalDashboard(
                all.size(),
                statusBreakdown.getOrDefault(ApprovalStatus.PENDING.name(), 0L),
                statusBreakdown.getOrDefault(ApprovalStatus.APPROVED.name(), 0L),
                statusBreakdown.getOrDefault(ApprovalStatus.FINAL_APPROVED.name(), 0L),
                statusBreakdown.getOrDefault(ApprovalStatus.REJECTED.name(), 0L),
                statusBreakdown.getOrDefault(ApprovalStatus.MODIFIED.name(), 0L),
                statusBreakdown.getOrDefault(ApprovalStatus.TIMEOUT.name(), 0L),
                statusBreakdown.getOrDefault(ApprovalStatus.ABORTED.name(), 0L),
                terminalCount,
                statusBreakdown,
                riskBreakdown,
                recentPending
        );
    }

    /**
     * 遍历所有状态，合并成全量请求列表。
     *
     * <p>把「全表扫描」拆成「逐状态查询再合并」，语义清晰且复用既有接口。</p>
     */
    private List<ApprovalRequest> loadAll() {
        List<ApprovalRequest> all = new java.util.ArrayList<>();
        for (ApprovalStatus s : ApprovalStatus.values()) {
            all.addAll(approvalRepository.findByStatus(s));
        }
        return all;
    }

    private ApprovalDashboard.PendingSummary toPendingSummary(ApprovalRequest r) {
        return new ApprovalDashboard.PendingSummary(
                r.getRequestId(),
                r.getAction().type(),
                r.getAction().description(),
                r.getRiskLevel().name(),
                r.getRequiredLevels(),
                r.getApprovedLevels()
        );
    }
}