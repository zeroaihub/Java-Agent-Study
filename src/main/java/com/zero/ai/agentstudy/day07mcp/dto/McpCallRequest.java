package com.zero.ai.agentstudy.day07mcp.dto;

import lombok.Data;

import java.util.Map;

/**
 * McpCallRequest —— Controller 层接收「调用某工具」请求的 DTO。
 *
 * <p>教学要点：Controller 不直接暴露内部协议模型（JsonRpcRequest），
 * 而是用面向前端友好的 DTO 承接入参，做到「对外接口」与「内部协议」解耦。</p>
 *
 * @author ZeroAi
 */
@Data
public class McpCallRequest {

    /** 要调用的工具名，如 get_weather */
    private String toolName;

    /** 调用参数，如 {"city":"北京"} */
    private Map<String, Object> arguments;
}