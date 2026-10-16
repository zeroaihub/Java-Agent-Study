package com.zero.ai.agentstudy.day14wechatagent.wechatapi.web;

import com.zero.ai.agentstudy.day14wechatagent.wechatapi.dto.ApiResponse;
import com.zero.ai.agentstudy.day14wechatagent.wechatnotify.application.WebhookInboundService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook 回调接收接口（入站适配器）。
 *
 * <p>企业微信、第三方系统通过 Webhook 把事件回调到本平台。本 Controller 负责
 * 从 HTTP 请求里取出事件 ID、原始报文、签名，交给应用服务
 * {@link WebhookInboundService} 完成「验签 -> 幂等 -> 处理」三道关口，
 * 再把处理结果翻译成统一响应。</p>
 *
 * <p><strong>安全提示：</strong>验签密钥从配置读取（{@code notify.webhook.secret}），
 * 生产应来自密钥管理服务，切勿硬编码或提交到代码库。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
@RestController
@RequestMapping("/api/webhook")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final WebhookInboundService inboundService;

    /** 验签共享密钥（配置注入，默认值仅供本地演示）。 */
    private final String webhookSecret;

    /**
     * 构造函数注入。
     *
     * @param inboundService Webhook 入站服务
     * @param webhookSecret  验签密钥（配置项 notify.webhook.secret）
     */
    public WebhookController(WebhookInboundService inboundService,
                             @Value("${notify.webhook.secret:demo-secret}") String webhookSecret) {
        this.inboundService = inboundService;
        this.webhookSecret = webhookSecret;
    }

    /**
     * 接收企业微信/第三方 Webhook 回调。
     *
     * @param eventId   事件唯一 ID（请求头 X-Event-Id，用于幂等）
     * @param signature 请求头携带的签名（X-Signature，十六进制 HMAC-SHA256）
     * @param payload   原始报文
     * @return 处理结果（ACCEPTED / DUPLICATE / REJECTED）
     */
    @PostMapping("/wechat")
    public ApiResponse<String> receive(
            @RequestHeader("X-Event-Id") String eventId,
            @RequestHeader("X-Signature") String signature,
            @RequestBody String payload) {

        log.info("[API] 收到 Webhook 回调，eventId={}", eventId);

        WebhookInboundService.InboundResult result =
                inboundService.receive(eventId, payload, signature, webhookSecret);

        // 验签失败返回业务错误码，其余情况返回成功（DUPLICATE 也视为已妥善处理）
        return switch (result.status()) {
            case REJECTED -> ApiResponse.fail(ApiResponse.CODE_BIZ_ERROR, result.message());
            case DUPLICATE, ACCEPTED -> ApiResponse.ok(result.message());
        };
    }
}