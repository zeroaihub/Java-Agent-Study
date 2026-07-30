package com.zero.ai.agentstudy.day13officeagent.officecore.domain.port;

import java.util.List;

/**
 * 邮件发送端口（MailSender）——出站端口。
 *
 * <p>交付阶段"把周报作为附件发送给销售总监"就依赖此端口。领域只描述"要发一封什么样的邮件"，
 * 而"用 SMTP 还是企业邮件网关"由适配器实现（例如基于 Jakarta Mail 的 {@code SmtpMailSender}）。</p>
 *
 * @author zero
 */
public interface MailSender {

    /**
     * 发送一封邮件。
     *
     * @param message 邮件内容与收件人信息
     * @return 发送结果
     */
    SendResult send(MailMessage message);

    /**
     * 邮件消息值对象。
     *
     * @param to          收件人列表
     * @param cc          抄送列表
     * @param subject     主题
     * @param body        正文（可为 HTML 或纯文本）
     * @param html        正文是否为 HTML
     * @param attachments 附件列表
     * @author zero
     */
    record MailMessage(List<String> to, List<String> cc, String subject,
                       String body, boolean html, List<Attachment> attachments) {

        public MailMessage {
            to = to == null ? List.of() : List.copyOf(to);
            cc = cc == null ? List.of() : List.copyOf(cc);
            subject = subject == null ? "" : subject;
            body = body == null ? "" : body;
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }

        /** 快捷工厂：单收件人纯文本邮件（无附件）。 */
        public static MailMessage text(String to, String subject, String body) {
            return new MailMessage(List.of(to), List.of(), subject, body, false, List.of());
        }
    }

    /**
     * 邮件附件值对象。
     *
     * @param filename    附件文件名
     * @param mediaType   MIME 类型
     * @param content     附件字节内容
     * @author zero
     */
    record Attachment(String filename, String mediaType, byte[] content) {

        public Attachment {
            filename = filename == null ? "attachment" : filename;
            mediaType = mediaType == null ? "application/octet-stream" : mediaType;
            content = content == null ? new byte[0] : content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    /**
     * 发送结果值对象。
     *
     * @param success   是否成功
     * @param messageId 邮件服务返回的消息 ID
     * @param error     失败原因（成功时为空）
     * @author zero
     */
    record SendResult(boolean success, String messageId, String error) {

        /** 成功结果。 */
        public static SendResult ok(String messageId) {
            return new SendResult(true, messageId, "");
        }

        /** 失败结果。 */
        public static SendResult fail(String error) {
            return new SendResult(false, "", error);
        }
    }
}