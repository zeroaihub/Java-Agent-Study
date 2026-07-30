package com.zero.ai.agentstudy.day11humanintheloop.humancore.spi;

import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.AgentAction;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.RiskLevel;

/**
 * 风险策略（SPI / Service Provider Interface）。
 *
 * <p>它回答一个问题：<b>"Agent 想干的这件事，风险有多高？"</b>
 * 风险等级决定了后续是否要拦截、要不要人工审批、要几级审批。</p>
 *
 * <p>做成接口（SPI）而不是写死的原因：不同企业、不同业务线的"风险"定义天差地别。
 * 电商可能认为"删订单"是高危；金融可能认为"转账 > 1 万"才是高危；内容平台
 * 可能认为"群发通知"是高危。把它抽象成 SPI，让每个接入方按自己的规则实现，
 * 内核代码完全不用改——这就是"对扩展开放、对修改关闭"。</p>
 *
 * <p>典型实现方式：规则引擎、配置中心下发、甚至用 LLM 打分。</p>
 */
@FunctionalInterface
public interface RiskPolicy {

    /**
     * 评估一个动作的风险等级。
     *
     * @param action 待评估的 Agent 动作
     * @return 风险等级，绝不返回 null（无风险应返回 {@link RiskLevel#NONE}）
     */
    RiskLevel evaluate(AgentAction action);
}