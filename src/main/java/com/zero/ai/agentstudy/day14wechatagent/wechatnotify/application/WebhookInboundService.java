package com.zero.ai.agentstudy.day14wechatagent.wechatnotify.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Webhook 入站接收服务（application 层）。
 *
 * <p>企业微信、第三方系统会通过 Webhook 把事件回调到本平台（如消息回执、审批结果、
 * 外部系统状态变更）。安全接收回调有三道关口，本服务负责前两道：</p>
 * <ol>
 *     <li><strong>验签</strong>：用共享密钥对 payload 计算 HMAC-SHA256，与请求头签名比对，防伪造。</li>
 *     <li><strong>幂等</strong>：同一 eventId 只处理一次，防止重复回调导致重复副作用。</li>
 *     <li><strong>异步处理</strong>：验签幂等通过后，业务处理应异步化（本教学版同步演示）。</li>
 * </ol>
 *
 * <p><strong>生产提示：</strong>幂等去重集合应用 Redis（带 TTL）而非内存 Set，
 * 且验签密钥应从配置中心/密钥管理服务读取，切勿硬编码。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
@Service
public class WebhookInboundService {

    private static final Logger log = LoggerFactory.getLogger(WebhookInboundService.class);

    private static final String HMAC_ALGO = "HmacSHA256";

    /** 已处理事件 ID 集合（幂等去重；生产用 Redis 带 TTL 替换）。 */
    private final Set<String> processedEvents = ConcurrentHashMap.newKeySet();

    /**
     * 接收并处理一条 Webhook 回调。
     *
     * @param eventId   事件唯一 ID（用于幂等）
     * @param payload   回调原始报文
     * @param signature 请求头携带的签名（十六进制）
     * @param secret    与调用方约定的共享密钥
     * @return 处理结果
     */
    public InboundResult receive(String eventId, String payload, String signature, String secret) {
        // 1) 验签
        String expected = hmacHex(payload, secret);
        if (!constantTimeEquals(expected, signature)) {
            log.warn("[Webhook] eventId={} 验签失败，拒绝处理", eventId);
            return InboundResult.rejected("签名校验失败");
        }

        // 2) 幂等：已处理过的事件直接返回
        if (!processedEvents.add(eventId)) {
            log.info("[Webhook] eventId={} 重复回调，幂等跳过", eventId);
            return InboundResult.duplicate();
        }

        // 3) 业务处理（教学版同步演示；生产应投递到队列异步处理）
        log.info("[Webhook] eventId={} 验签+幂等通过，处理回调 payload 长度={}", eventId, payload.length());
        // TODO 生产：解析 payload -> 分发到对应的领域处理器（消息回执/审批结果/外部事件）

        return InboundResult.accepted();
    }

    /**
     * 计算 payload 的 HMAC-SHA256 十六进制签名。
     *
     * @param payload 报文
     * @param secret  密钥
     * @return 十六进制签名
     */
    private String hmacHex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    /**
     * 常量时间比较，防止时序攻击（timing attack）。
     *
     * @param a 期望签名
     * @param b 实际签名
     * @return 是否相等
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    /**
     * 回调处理结果。
     *
     * @param status 结果状态
     * @param message 说明
     */
    public record InboundResult(Status status, String message) {

        /** 结果状态枚举。 */
        public enum Status { ACCEPTED, DUPLICATE, REJECTED }

        static InboundResult accepted() {
            return new InboundResult(Status.ACCEPTED, "已接收");
        }

        static InboundResult duplicate() {
            return new InboundResult(Status.DUPLICATE, "重复回调，已幂等跳过");
        }

        static InboundResult rejected(String reason) {
            return new InboundResult(Status.REJECTED, reason);
        }
    }
}