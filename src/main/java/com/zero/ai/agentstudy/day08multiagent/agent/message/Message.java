package com.zero.ai.agentstudy.day08multiagent.agent.message;

import com.zero.ai.agentstudy.day08multiagent.agent.core.AgentRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Message —— Agent 之间通信的「标准消息模型」。
 *
 * <p>教学要点：第二章讲过，无论采用哪种通信方式（直接消息/黑板/消息总线），
 * 都需要一个统一的消息载体。Message 就是这个载体，它回答四个问题：
 * <b>谁发的（from）、发给谁（to）、什么类型（type）、内容是什么（content）、什么时候（timestamp）</b>。</p>
 *
 * <p>本项目 V1 以黑板通信为主，Message 主要用于「协作过程记录」与未来消息总线扩展的预留。
 * 把它设计出来，是为了让通信这件事「有据可依、可追溯」。</p>
 *
 * @author ZeroAi
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Message {

    /**
     * 消息类型。
     */
    public enum Type {
        /** 分派任务（Coordinator → Agent） */
        DISPATCH,
        /** 产出结果（Agent → Coordinator/黑板） */
        RESULT,
        /** 评审反馈（Reviewer → Writer，用于反思闭环） */
        FEEDBACK
    }

    /** 发送者角色（Coordinator 用 null 表示中枢） */
    private AgentRole from;

    /** 接收者角色 */
    private AgentRole to;

    /** 消息类型 */
    private Type type;

    /** 消息内容 */
    private String content;

    /** 产生时间 */
    private LocalDateTime timestamp;

    /**
     * 便捷工厂：创建一条消息并自动打上当前时间戳。
     *
     * @param from    发送者
     * @param to      接收者
     * @param type    类型
     * @param content 内容
     * @return 消息
     */
    public static Message of(AgentRole from, AgentRole to, Type type, String content) {
        return Message.builder()
                .from(from)
                .to(to)
                .type(type)
                .content(content)
                .timestamp(LocalDateTime.now())
                .build();
    }
}