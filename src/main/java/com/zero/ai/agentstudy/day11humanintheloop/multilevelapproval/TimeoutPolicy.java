package com.zero.ai.agentstudy.day11humanintheloop.multilevelapproval;

/**
 * 超时策略（Timeout Policy）——决定「一个审批请求到点没人批」时该怎么办。
 *
 * <p>企业里对「超时」的处理并不统一，取决于业务对「安全」还是「效率」的偏好：</p>
 * <ul>
 *   <li>{@link #REJECT}：超时即拒。最保守，宁可错杀不可放过。适合高危动作
 *       （如删库、大额转账）——没人在规定时间内明确批准，就默认不许干。</li>
 *   <li>{@link #ESCALATE}：超时升级。把请求「甩锅」给更高一级或专门的兜底审批人，
 *       避免流程卡死。适合「必须有人拍板、不能无限等」的关键流程。</li>
 *   <li>{@link #AUTO_APPROVE}：超时自动通过。最激进，只适合低风险、追求吞吐的场景
 *       （如内部低敏操作）。<b>高危动作严禁使用</b>，否则超时就成了「绕过审批」的后门。</li>
 * </ul>
 *
 * <p>把策略抽象成枚举而非写死 if/else，好处是：不同风险等级 / 不同审批链可以挂不同策略，
 * 且新增一种策略只是加一个枚举值 + 一段处理分支，符合开闭原则。</p>
 */
public enum TimeoutPolicy {

    /** 超时即拒绝（进入终态 REJECTED，最安全）。 */
    REJECT("超时拒绝"),

    /** 超时升级到兜底审批人（继续等，但换人批）。 */
    ESCALATE("超时升级"),

    /** 超时自动通过（仅限低风险，慎用）。 */
    AUTO_APPROVE("超时自动通过");

    private final String label;

    TimeoutPolicy(String label) {
        this.label = label;
    }

    /**
     * 中文可读标签，便于日志和前端展示。
     */
    public String label() {
        return label;
    }
}