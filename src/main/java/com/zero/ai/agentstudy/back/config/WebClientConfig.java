package com.zero.ai.agentstudy.back.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * WebClient 配置
 * WebClient 基于 Reactor 非阻塞, 是调用大模型 API 的最佳选择:
 * 1. 支持流式响应(Server-Sent Events)
 * 2. 异步非阻塞, 适合高并发
 * 3. 与 Spring Boot 整合好
 *
 * @author ZeroAi
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient aiWebClient(AiProperties properties) {
        HttpClient httpClient = HttpClient.create()
                // 连接建立超时: 仅 TCP 握手, 几秒即可
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.getConnectTimeout())
                // 响应超时: 等待大模型"思考 + 首字节"的最长时间
                .responseTimeout(Duration.ofMillis(properties.getTimeout()))
                // 读写超时: 数据传输阶段, 防止大模型长时间不返回数据导致连接被判死
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(properties.getTimeout(), TimeUnit.MILLISECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(properties.getTimeout(), TimeUnit.MILLISECONDS)));

        return WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }
}
