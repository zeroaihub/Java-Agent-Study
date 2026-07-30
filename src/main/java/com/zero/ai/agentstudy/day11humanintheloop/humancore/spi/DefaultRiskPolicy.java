package com.zero.ai.agentstudy.day11humanintheloop.humancore.spi;

import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.AgentAction;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.RiskLevel;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 默认风险策略：一个"够用的、可演示的"参考实现。
 *
 * <p>规则（可按需覆盖）：</p>
 * <ol>
 *   <li>动作类型属于"高危集合"（如删库、删订单、批量删除）→ {@link RiskLevel#HIGH}。</li>
 *   <li>涉及金额且金额 &ge; 高危阈值（默认 1 万）→ {@link RiskLevel#HIGH}。</li>
 *   <li>涉及金额且金额 > 0 → {@link RiskLevel#LOW}。</li>
 *   <li>其它（纯查询、读数据等）→ {@link RiskLevel#NONE}。</li>
 * </ol>
 *
 * <p>这是"约定优于配置"的体现：给一个合理默认值，接入方不写策略也能跑；
 * 需要定制时再实现自己的 {@link RiskPolicy} 替换即可。</p>
 */
public class DefaultRiskPolicy implements RiskPolicy {

    /** 高危动作类型集合。 */
    private static final Set<String> HIGH_RISK_TYPES = Set.of(
            "DB_DELETE",
            "DB_DROP",
            "BATCH_DELETE",
            "ORDER_DELETE",
            "USER_DELETE",
            "REFUND"
    );

    /** 金额高危阈值。 */
    private final BigDecimal highAmountThreshold;

    public DefaultRiskPolicy() {
        this(new BigDecimal("10000"));
    }

    public DefaultRiskPolicy(BigDecimal highAmountThreshold) {
        this.highAmountThreshold = highAmountThreshold;
    }

    @Override
    public RiskLevel evaluate(AgentAction action) {
        // 规则 1：高危动作类型
        String type = action.type() == null ? "" : action.type().toUpperCase();
        if (HIGH_RISK_TYPES.contains(type)) {
            return RiskLevel.HIGH;
        }

        // 规则 2 & 3：按金额判定
        if (action.hasAmount()) {
            if (action.amount().compareTo(highAmountThreshold) >= 0) {
                return RiskLevel.HIGH;
            }
            return RiskLevel.LOW;
        }

        // 规则 4：默认无风险
        return RiskLevel.NONE;
    }
}