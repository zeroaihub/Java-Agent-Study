package com.zero.ai.agentstudy.day14wechatagent.wechatapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发送企业微信 Markdown 通知的请求体（入站 DTO）。
 *
 * <p>DTO 与领域模型（{@code Notification}）刻意分离：DTO 是「协议层」对象，
 * 只关心外部传参与校验；领域模型是「业务层」对象，关心不变量与状态迁移。
 * Controller 负责把 DTO 翻译成领域对象，避免协议细节污染领域。</p>
 *
 * <p>用 Bean Validation 注解声明式校验，配合全局异常处理器，
 * 非法参数会被统一转成 400 响应。</p>
 *
 * @param tenantId 租户 ID（多租户隔离，必填）
 * @param groupId  企业微信群 ID（接收方，必填）
 * @param title    通知标题（必填）
 * @param markdown Markdown 正文（必填，长度受企业微信 4096 字节上限约束）
 * @param dedupeKey 幂等键（可空；带上则防止重复投递）
 * @author ZeroHub AI Agent Platform
 */
public record SendNotificationRequest(

        @NotBlank(message = "租户 ID 不能为空")
        String tenantId,

        @NotBlank(message = "群 ID 不能为空")
        String groupId,

        @NotBlank(message = "标题不能为空")
        @Size(max = 128, message = "标题长度不能超过 128")
        String title,

        @NotBlank(message = "Markdown 正文不能为空")
        String markdown,

        String dedupeKey
) {
}