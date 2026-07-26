package com.zero.ai.agentstudy.back.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 大模型服务配置
 * 对应 application.yml 中的 ai.provider
 *
 * @author ZeroAi
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai.provider")
public class AiProperties {

    /** 服务地址, 如 https://api.deepseek.com */
    private String baseUrl;

    /** API Key */
    private String apiKey;

    /** 默认模型名 */
    private String model;

    /**
     * 响应读取超时(ms): 等待大模型"思考 + 生成"的最长时间。
     * 大模型推理慢, 这里要给足, 默认 10 分钟。
     */
    private long timeout = 600000L;

    /**
     * 连接建立超时(ms): 仅 TCP 握手阶段, 几秒足够, 不应和响应超时混用。
     */
    private long connectTimeout = 10000L;
}
