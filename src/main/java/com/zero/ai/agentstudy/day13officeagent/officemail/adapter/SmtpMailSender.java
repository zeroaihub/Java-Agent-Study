package com.zero.ai.agentstudy.day13officeagent.officemail.adapter;

import com.zero.ai.agentstudy.day13officeagent.officecore.domain.port.MailSender;
import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * SMTP 邮件发送适配器——{@link MailSender} 出站端口的 Jakarta Mail 实现。
 *
 * <p><b>六边形架构中的位置：</b> 领域与 Pipeline 只描述"要发一封什么样的邮件"（{@link MailMessage}），
 * 至于用 SMTP、企业邮件网关还是第三方 API 发送，全被隔离在本适配器里。终极场景"把周报作为
 * 附件发送给销售总监"最终就落在这里——上层把渲染好的 .pptx/.docx 字节作为 {@link Attachment}
 * 传入，本适配器负责组装 MIME 多部件消息并投递。</p>
 *
 * <p><b>为什么用 Jakarta Mail 而非 Spring 的 JavaMailSender？</b> 本项目刻意把邮件依赖收敛到
 * 适配器内部，避免领域层间接感知 Spring Mail 的类型；直接使用 Jakarta Mail 的原生 API 也更
 * 便于演示 MIME 多部件（正文 + 多附件）的组装细节，教学价值更高。生产中若已重度使用 Spring Mail，
 * 完全可以在本类内部替换实现，端口契约不变。</p>
 *
 * <p><b>连接参数外置：</b> SMTP 主机、端口、账号、密码通过 {@code @Value} 从配置注入，
 * 绝不硬编码——密码等敏感信息应放在环境变量或密钥管理服务中，而非源码或明文配置。</p>
 *
 * @author zero
 */
@Component
public class SmtpMailSender implements MailSender {

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String from;
    private final boolean auth;
    private final boolean startTls;

    /**
     * 构造适配器，从配置注入 SMTP 连接参数。
     *
     * @param host     SMTP 主机
     * @param port     SMTP 端口
     * @param username 认证用户名
     * @param password 认证密码（应来自环境变量/密钥管理）
     * @param from     发件人地址
     * @param auth     是否需要认证
     * @param startTls 是否启用 STARTTLS
     */
    public SmtpMailSender(
            @Value("${office.mail.host:localhost}") String host,
            @Value("${office.mail.port:25}") int port,
            @Value("${office.mail.username:}") String username,
            @Value("${office.mail.password:}") String password,
            @Value("${office.mail.from:no-reply@example.com}") String from,
            @Value("${office.mail.auth:false}") boolean auth,
            @Value("${office.mail.starttls:false}") boolean startTls) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.from = from;
        this.auth = auth;
        this.startTls = startTls;
    }

    @Override
    public SendResult send(MailMessage message) {
        try {
            Session session = createSession();
            MimeMessage mime = new MimeMessage(session);
            mime.setFrom(new InternetAddress(from));

            // 收件人与抄送
            for (String to : message.to()) {
                mime.addRecipient(Message.RecipientType.TO, new InternetAddress(to));
            }
            for (String cc : message.cc()) {
                mime.addRecipient(Message.RecipientType.CC, new InternetAddress(cc));
            }
            mime.setSubject(message.subject(), "UTF-8");

            // 组装 MIME 多部件：一个正文部件 + 若干附件部件
            MimeMultipart multipart = new MimeMultipart();
            multipart.addBodyPart(buildBodyPart(message));
            for (Attachment att : message.attachments()) {
                multipart.addBodyPart(buildAttachmentPart(att));
            }
            mime.setContent(multipart);

            Transport.send(mime);
            // Jakarta Mail 不直接回传服务端 messageId，这里用消息自身生成的 ID 兜底
            String messageId = mime.getMessageID() == null ? "sent" : mime.getMessageID();
            return SendResult.ok(messageId);
        } catch (MessagingException e) {
            // 失败不抛异常打断 Pipeline，而是以结果对象回传，交由上层决定重试或告警
            return SendResult.fail(e.getMessage());
        }
    }

    /**
     * 依据配置创建 SMTP 会话；启用认证时提供 Authenticator。
     *
     * @return Jakarta Mail 会话
     */
    private Session createSession() {
        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.auth", String.valueOf(auth));
        props.put("mail.smtp.starttls.enable", String.valueOf(startTls));

        if (auth) {
            return Session.getInstance(props, new jakarta.mail.Authenticator() {
                @Override
                protected jakarta.mail.PasswordAuthentication getPasswordAuthentication() {
                    return new jakarta.mail.PasswordAuthentication(username, password);
                }
            });
        }
        return Session.getInstance(props);
    }

    /**
     * 构造正文部件，按 {@link MailMessage#html()} 决定 Content-Type。
     *
     * @param message 邮件消息
     * @return 正文 MIME 部件
     * @throws MessagingException 组装失败
     */
    private MimeBodyPart buildBodyPart(MailMessage message) throws MessagingException {
        MimeBodyPart bodyPart = new MimeBodyPart();
        if (message.html()) {
            bodyPart.setContent(message.body(), "text/html; charset=UTF-8");
        } else {
            bodyPart.setText(message.body(), "UTF-8");
        }
        return bodyPart;
    }

    /**
     * 把附件字节封装为一个 MIME 附件部件。
     *
     * @param att 附件值对象
     * @return 附件 MIME 部件
     * @throws MessagingException 组装失败
     */
    private MimeBodyPart buildAttachmentPart(Attachment att) throws MessagingException {
        MimeBodyPart part = new MimeBodyPart();
        ByteArrayDataSource source = new ByteArrayDataSource(att.content(), att.mediaType());
        part.setDataHandler(new DataHandler(source));
        part.setFileName(att.filename());
        return part;
    }
}