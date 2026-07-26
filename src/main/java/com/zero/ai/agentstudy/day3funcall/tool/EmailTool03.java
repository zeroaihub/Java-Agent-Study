package com.zero.ai.agentstudy.day3funcall.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 邮件发送工具（第六章）
 *
 * 教学要点：
 * 1. 这是一个"写操作/有副作用"的工具（真实会发邮件），必须做幂等与参数校验。
 * 2. 它常作为 Workflow 的最后一环：前一个工具（如 WeatherTool）的输出，
 *    经 LLM 组织后作为本工具的 content 入参 —— 这就是"工具协同"。
 * 3. 返回结构化 JSON（含 messageId），便于追踪与去重。
 *
 * @author ZeroAi
 */
@Slf4j
@Component
public class EmailTool03 {

    /**
     * 发送一封邮件（模拟）。
     *
     * @param to      收件人邮箱
     * @param subject 邮件主题
     * @param content 邮件正文
     * @return 结构化 JSON（含 messageId / status）
     */
    @Tool(description = "发送邮件给指定收件人。当用户需要把信息/通知/报告发送到某个邮箱时使用")
    public String sendEmail(
            @ToolParam(description = "收件人邮箱地址，如 user@example.com") String to,
            @ToolParam(description = "邮件主题") String subject,
            @ToolParam(description = "邮件正文内容") String content) {

        log.info("[EmailTool] 被调用, to={}, subject={}", to, subject);

        // 1. 参数校验前置
        if (to == null || !to.matches("^[\\w.-]+@[\\w.-]+\\.[a-zA-Z]{2,}$")) {
            return "{\"code\":\"INVALID_PARAM\",\"message\":\"收件人邮箱格式不正确\"}";
        }
        if (subject == null || subject.isBlank()) {
            return "{\"code\":\"INVALID_PARAM\",\"message\":\"邮件主题不能为空\"}";
        }

        // 2. 真实项目：调 JavaMailSender / 邮件网关发送。这里模拟发送成功。
        //    真实场景应基于业务唯一键做幂等，防止 LLM 重复调用导致重复发送。
        String messageId = "msg_" + UUID.randomUUID().toString().substring(0, 8);

        log.info("[EmailTool] 邮件已发送(模拟), messageId={}, contentLen={}",
                messageId, content == null ? 0 : content.length());

        // 3. 返回结构化 JSON
        return String.format(
                "{\"messageId\":\"%s\",\"to\":\"%s\",\"subject\":\"%s\",\"status\":\"SENT\"}",
                messageId, to, subject);
    }
}