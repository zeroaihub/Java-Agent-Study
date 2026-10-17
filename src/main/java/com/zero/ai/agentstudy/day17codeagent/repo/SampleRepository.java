package com.zero.ai.agentstudy.day17codeagent.repo;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 贯穿 Day17 全部 Demo 的<b>迷你仓库夹具</b>。
 *
 * <p>为什么不用真实 git 仓库：Demo 必须<b>零依赖、可离线、可重复</b>。
 * 这份夹具是一段固定代码，任何人在任何机器上跑出来的结果都一模一样，
 * 这正是「教程可验证」的前提。</p>
 *
 * <p>它刻意设计成一个有代表性的小工程：</p>
 * <ul>
 *     <li>一个含业务逻辑的服务类 {@code OrderService}（后续章节给它加功能、改 bug）；</li>
 *     <li>一个值对象 {@code Money}（演示「符号检索」如何找到它）；</li>
 *     <li>一个仓储接口 {@code OrderRepository}（演示依赖图）；</li>
 *     <li>一个策略类 {@code DiscountPolicy}（演示跨文件引用）；</li>
 *     <li>一份 {@code README.md}（演示非代码文件的处理）。</li>
 * </ul>
 *
 * @author ZeroAi
 */
public final class SampleRepository {

    public static final String ORDER_SERVICE = "src/main/java/demo/order/OrderService.java";
    public static final String MONEY = "src/main/java/demo/order/Money.java";
    public static final String ORDER_REPOSITORY = "src/main/java/demo/order/OrderRepository.java";
    public static final String DISCOUNT_POLICY = "src/main/java/demo/order/DiscountPolicy.java";
    public static final String README = "README.md";

    private SampleRepository() {
    }

    /** 返回「路径 → 内容」的有序视图。 */
    public static Map<String, String> files() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(README, readme());
        files.put(MONEY, money());
        files.put(ORDER_REPOSITORY, orderRepository());
        files.put(DISCOUNT_POLICY, discountPolicy());
        files.put(ORDER_SERVICE, orderService());
        return files;
    }

    /** 把夹具铺进一个新的临时仓库。 */
    public static VirtualFileSystem create() {
        VirtualFileSystem vfs = VirtualFileSystem.temp("sample-repo-" + System.nanoTime());
        vfs.writeAll(files());
        return vfs;
    }

    // =====================================================================
    // 文件内容
    // =====================================================================

    private static String readme() {
        return """
                # Order Demo

                一个用于演示 Code Agent 的最小订单工程。

                ## 模块
                - Money：金额值对象
                - OrderRepository：订单仓储接口
                - DiscountPolicy：折扣策略
                - OrderService：下单主流程
                """;
    }

    private static String money() {
        return """
                package demo.order;

                import java.math.BigDecimal;

                /**
                 * 金额值对象：不可变，单位为元。
                 */
                public final class Money {

                    private final BigDecimal amount;

                    private Money(BigDecimal amount) {
                        this.amount = amount;
                    }

                    public static Money of(long cents) {
                        return new Money(BigDecimal.valueOf(cents));
                    }

                    public static Money zero() {
                        return new Money(BigDecimal.ZERO);
                    }

                    public Money add(Money other) {
                        return new Money(this.amount.add(other.amount));
                    }

                    public boolean greaterThan(Money other) {
                        return this.amount.compareTo(other.amount) > 0;
                    }

                    public BigDecimal amount() {
                        return amount;
                    }
                }
                """;
    }

    private static String orderRepository() {
        return """
                package demo.order;

                import java.util.Optional;

                /**
                 * 订单仓储接口。
                 */
                public interface OrderRepository {

                    Optional<Order> findById(String orderId);

                    void save(Order order);

                    record Order(String orderId, long amountCents, boolean paid) {
                    }
                }
                """;
    }

    private static String discountPolicy() {
        return """
                package demo.order;

                /**
                 * 折扣策略：按订单金额阶梯打折。
                 */
                public class DiscountPolicy {

                    private static final long VIP_THRESHOLD = 100_00L;

                    public double discountFor(long amountCents, boolean vip) {
                        if (!vip) {
                            return 1.0;
                        }
                        if (amountCents >= VIP_THRESHOLD) {
                            return 0.8;
                        }
                        return 0.95;
                    }
                }
                """;
    }

    private static String orderService() {
        return """
                package demo.order;

                /**
                 * 下单主流程。
                 */
                public class OrderService {

                    private final OrderRepository repository;
                    private final DiscountPolicy discountPolicy;

                    public OrderService(OrderRepository repository, DiscountPolicy discountPolicy) {
                        this.repository = repository;
                        this.discountPolicy = discountPolicy;
                    }

                    public Money checkout(String orderId, boolean vip) {
                        OrderRepository.Order order = repository.findById(orderId)
                                .orElseThrow(() -> new IllegalArgumentException("order not found: " + orderId));
                        double rate = discountPolicy.discountFor(order.amountCents(), vip);
                        return Money.of(Math.round(order.amountCents() * rate));
                    }

                    public boolean isPaid(String orderId) {
                        return repository.findById(orderId)
                                .map(OrderRepository.Order::paid)
                                .orElse(false);
                    }
                }
                """;
    }
}
