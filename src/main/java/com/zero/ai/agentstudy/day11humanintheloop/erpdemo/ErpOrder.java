package com.zero.ai.agentstudy.day11humanintheloop.erpdemo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * ERP 订单（实战演示用的业务实体）。
 *
 * <p>这是 Chapter 09「ERP 批量删单实战」里被 Agent 操作的<b>真实业务对象</b>。
 * 它刻意做得很简单——只保留演示删单审批流程所必需的字段：订单号、客户、金额、
 * 是否为测试单、是否已删除。真实 ERP 里一张订单有几十上百个字段，但那些与我们
 * 要讲的「高危操作必须人工审批」这条主线无关。</p>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>用 {@code deleted} 软删除标记而非物理移除——这样审批链、审计日志里引用的
 *       订单永远查得到，符合企业「删除也要留痕」的合规要求。</li>
 *   <li>{@code testOrder} 字段用来演示「批量删除测试订单」这个典型高危场景：
 *       一旦 SQL 条件写错，可能把生产订单也一起删了，所以必须人工把关。</li>
 * </ul>
 */
public class ErpOrder {

    /** 订单号（业务主键）。 */
    private final String orderId;

    /** 客户名。 */
    private final String customer;

    /** 订单金额。 */
    private final BigDecimal amount;

    /** 是否为测试订单（true = 测试数据，允许被清理）。 */
    private final boolean testOrder;

    /** 创建时间。 */
    private final Instant createdAt;

    /** 软删除标记（true = 已被删除，逻辑上不可见）。 */
    private boolean deleted;

    public ErpOrder(String orderId, String customer, BigDecimal amount, boolean testOrder) {
        this.orderId = Objects.requireNonNull(orderId, "orderId 不能为空");
        this.customer = Objects.requireNonNull(customer, "customer 不能为空");
        this.amount = (amount == null) ? BigDecimal.ZERO : amount;
        this.testOrder = testOrder;
        this.createdAt = Instant.now();
        this.deleted = false;
    }

    /** 执行软删除。 */
    public void markDeleted() {
        this.deleted = true;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getCustomer() {
        return customer;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public boolean isTestOrder() {
        return testOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isDeleted() {
        return deleted;
    }
}