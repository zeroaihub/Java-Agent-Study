package com.zero.ai.agentstudy.day07mcp.mcp.tool;

import com.zero.ai.agentstudy.day07mcp.entity.CallToolResult;
import com.zero.ai.agentstudy.day07mcp.entity.ToolDefinition;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * TimeTool —— 查询指定时区当前时间的 MCP 工具。
 *
 * <p>教学要点：这是一个「无必填参数」的工具示范——参数 timezone 可选，
 * 缺省时用系统默认时区。它说明工具的 inputSchema 中 required 可以为空数组，
 * 模型据此知道「不传参数也能调用」。</p>
 *
 * @author ZeroAi
 */
@Component
public class TimeTool implements McpTool {

    /** 工具名 */
    public static final String TOOL_NAME = "get_current_time";

    /** 统一的时间格式 */
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public ToolDefinition definition() {
        // 入参：timezone（字符串，可选）
        Map<String, Object> tzProp = Map.of(
                "type", "string",
                "description", "IANA 时区标识，如 Asia/Shanghai、America/New_York；不传则用服务器默认时区"
        );
        Map<String, Object> properties = Map.of("timezone", tzProp);
        return ToolDefinition.of(
                TOOL_NAME,
                "获取当前日期和时间。当用户询问现在几点、今天日期时使用；可指定时区。",
                properties,
                new String[]{} // 无必填参数
        );
    }

    @Override
    public CallToolResult execute(Map<String, Object> arguments) {
        ZoneId zoneId;
        // 1) 解析时区参数：非法时区以业务失败返回，交给模型/用户修正
        Object tzArg = arguments == null ? null : arguments.get("timezone");
        if (tzArg != null && !String.valueOf(tzArg).trim().isEmpty()) {
            String tz = String.valueOf(tzArg).trim();
            try {
                zoneId = ZoneId.of(tz);
            } catch (Exception e) {
                return CallToolResult.fail("无法识别的时区：" + tz
                        + "，请使用如 Asia/Shanghai 的 IANA 时区标识");
            }
        } else {
            zoneId = ZoneId.systemDefault();
        }

        // 2) 取当前时间并格式化
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        String text = "当前时间（" + zoneId + "）：" + now.format(FMT);
        return CallToolResult.ok(text);
    }
}