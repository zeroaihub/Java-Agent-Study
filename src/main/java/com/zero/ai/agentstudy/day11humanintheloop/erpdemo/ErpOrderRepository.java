package com.zero.ai.agentstudy.day11humanintheloop.erpdemo;

import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ERP 订单仓储（实战演示用的内存数据源）。
 *
 * <p>它模拟一个真实 ERP 系统的订单表：启动时预置若干订单，其中<b>混杂了测试单和
 * 生产单</b>——这正是「批量删除测试订单」这个高危场景的真实土壤。如果删除条件写错，
 * 就可能误删生产订单，造成资损。因此本章要演示：删单动作必须先过人工审批，审批通过
 * 后才真正落库删除。</p>
 *
 * <p>用 {@link ConcurrentHashMap} 存储，用软删除（{@link ErpOrder#markDeleted()}）
 * 而非物理移除，保证审计可追溯。生产环境把本类替换为 JPA / MyBatis 实现即可，
 * 上层的删单 Agent 与审批流程一行都不用改。</p>
 */
@Repository
public class ErpOrderRepository {

    private final Map<String, ErpOrder> store = new ConcurrentHashMap<>();

    public ErpOrderRepository() {
        seed();
    }

    /**
     * 预置演示数据：3 张测试单 + 2 张生产单。
     * <p>刻意让测试单和生产单混在一起，凸显「批量删除」的风险。</p>
     */
    private void seed() {
        save(new ErpOrder("T-1001", "测试账号A", new BigDecimal("0.01"), true));
        save(new ErpOrder("T-1002", "测试账号B", new BigDecimal("0.01"), true));
        save(new ErpOrder("T-1003", "压测机器人", new BigDecimal("0.00"), true));
        save(new ErpOrder("P-2001", "华为技术有限公司", new BigDecimal("128000.00"), false));
        save(new ErpOrder("P-2002", "中国银行股份有限公司", new BigDecimal("560000.00"), false));
    }

    /** 保存 / 更新一张订单。 */
    public ErpOrder save(ErpOrder order) {
        store.put(order.getOrderId(), order);
        return order;
    }

    /** 按订单号查询。 */
    public Optional<ErpOrder> findById(String orderId) {
        return Optional.ofNullable(store.get(orderId));
    }

    /** 查询所有「未删除」的订单。 */
    public List<ErpOrder> findAllActive() {
        return store.values().stream()
                .filter(o -> !o.isDeleted())
                .sorted((a, b) -> a.getOrderId().compareTo(b.getOrderId()))
                .collect(Collectors.toList());
    }

    /**
     * 查询所有「未删除的测试订单」——这就是删单 Agent 的目标集合。
     *
     * @return 命中的测试订单列表
     */
    public List<ErpOrder> findActiveTestOrders() {
        return store.values().stream()
                .filter(o -> !o.isDeleted())
                .filter(ErpOrder::isTestOrder)
                .sorted((a, b) -> a.getOrderId().compareTo(b.getOrderId()))
                .collect(Collectors.toList());
    }

    /**
     * 执行软删除：把指定订单号标记为已删除。
     *
     * <p><b>关键约束：</b>本方法是「真正动数据」的地方，只应在审批通过后被调用。
     * 删单 Agent 绝不能跳过审批直接调它——这正是 HITL 要守住的底线。</p>
     *
     * @param orderIds 待删除的订单号列表
     * @return 实际被删除的订单号（跳过不存在或已删除的）
     */
    public List<String> deleteByIds(List<String> orderIds) {
        return orderIds.stream()
                .map(store::get)
                .filter(o -> o != null && !o.isDeleted())
                .peek(ErpOrder::markDeleted)
                .map(ErpOrder::getOrderId)
                .collect(Collectors.toList());
    }

    /** 统计未删除订单总数（用于演示前后对比）。 */
    public long countActive() {
        return store.values().stream().filter(o -> !o.isDeleted()).count();
    }
}