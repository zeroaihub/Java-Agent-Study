package com.zero.ai.agentstudy.day08multiagent.agent.core;

/**
 * AgentRole —— Agent 的角色枚举。
 *
 * <p>教学要点：Multi-Agent 系统的核心是「分工」。每个 Agent 扮演一个明确、单一的角色，
 * 对应架构图里的一个方框。用枚举把角色固化下来，好处有三：</p>
 * <ul>
 *   <li>类型安全：Coordinator 分派任务时不会写错角色名（避免用魔法字符串）；</li>
 *   <li>可枚举：一眼看清系统里到底有哪些角色；</li>
 *   <li>可扩展：未来新增角色（如 TranslatorAgent）只需加一个枚举值。</li>
 * </ul>
 *
 * <p>本项目 V1 采用「顺序流水线」：PLANNER → RESEARCHER → WRITER → REVIEWER。</p>
 *
 * @author ZeroAi
 */
public enum AgentRole {

    /** 规划者：把大目标拆解成写作大纲（outline） */
    PLANNER("规划者", "把用户需求拆解成结构化写作大纲"),

    /** 研究者：根据大纲收集素材（materials） */
    RESEARCHER("研究者", "根据大纲收集每个小节的支撑素材"),

    /** 写作者：根据大纲+素材写出正文草稿（draft） */
    WRITER("写作者", "根据大纲与素材撰写完整 Markdown 正文"),

    /** 评审者：对草稿打分并给出修改意见（review/score） */
    REVIEWER("评审者", "独立审校草稿，给出分数与修改意见");

    /** 角色中文名（给人看的） */
    private final String displayName;

    /** 角色职责说明（也可作为 System Prompt 的一部分） */
    private final String responsibility;

    AgentRole(String displayName, String responsibility) {
        this.displayName = displayName;
        this.responsibility = responsibility;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getResponsibility() {
        return responsibility;
    }
}