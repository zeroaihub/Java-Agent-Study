package com.zero.ai.agentstudy.day14wechatagent.wechatmemory.domain.model;

/**
 * 记忆消息角色。
 *
 * <p>短期记忆（多轮对话）中的每一条消息都归属于某个角色，
 * 这一分类直接对应 LLM Chat API 的 {@code role} 字段（system/user/assistant），
 * 在把记忆拼装成提示词（prompt）时按角色映射即可。</p>
 *
 * <ul>
 *     <li>{@link #SYSTEM} —— 系统设定 / 人格设定，通常置于对话最前。</li>
 *     <li>{@link #USER} —— 用户输入。</li>
 *     <li>{@link #ASSISTANT} —— Agent（模型）的回复。</li>
 * </ul>
 *
 * @author ZeroHub AI Agent Platform
 */
public enum MessageRole {

    /** 系统设定，如人格、约束、企业规则。 */
    SYSTEM,

    /** 用户发出的消息。 */
    USER,

    /** Agent（模型）产生的回复。 */
    ASSISTANT
}