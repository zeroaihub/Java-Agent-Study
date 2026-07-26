package com.zero.ai.agentstudy.day07mcp.mcp.tool;

import com.zero.ai.agentstudy.day07mcp.entity.CallToolResult;
import com.zero.ai.agentstudy.day07mcp.entity.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * CalculatorTool —— 四则运算的 MCP 工具。
 *
 * <p>教学要点：这是一个「多参数 + 枚举约束」的工具示范。它接收三个参数：
 * 运算符 op（枚举：add/subtract/multiply/divide）、两个操作数 a 和 b。
 * 通过 JSON Schema 的 enum 字段，可以告诉模型「op 只能是这几个值之一」。</p>
 *
 * <p>它同时演示了「除零」这类典型业务失败如何优雅返回给模型（isError=true）。</p>
 *
 * @author ZeroAi
 */
@Component
public class CalculatorTool implements McpTool {

    /** 工具名 */
    public static final String TOOL_NAME = "calculate";

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public ToolDefinition definition() {
        // op：枚举约束
        Map<String, Object> opProp = Map.of(
                "type", "string",
                "description", "运算类型",
                "enum", new String[]{"add", "subtract", "multiply", "divide"}
        );
        // a、b：数值
        Map<String, Object> aProp = Map.of("type", "number", "description", "第一个操作数");
        Map<String, Object> bProp = Map.of("type", "number", "description", "第二个操作数");

        Map<String, Object> properties = Map.of(
                "op", opProp,
                "a", aProp,
                "b", bProp
        );
        return ToolDefinition.of(
                TOOL_NAME,
                "对两个数字进行四则运算（加/减/乘/除）。当用户需要精确计算时使用。",
                properties,
                new String[]{"op", "a", "b"}
        );
    }

    @Override
    public CallToolResult execute(Map<String, Object> arguments) {
        // 1) 参数完整性校验
        if (arguments == null
                || arguments.get("op") == null
                || arguments.get("a") == null
                || arguments.get("b") == null) {
            return CallToolResult.fail("缺少必填参数，需要 op、a、b 三个参数");
        }

        String op = String.valueOf(arguments.get("op")).trim();
        double a;
        double b;
        // 2) 数值解析：非数字以业务失败返回
        try {
            a = toDouble(arguments.get("a"));
            b = toDouble(arguments.get("b"));
        } catch (NumberFormatException e) {
            return CallToolResult.fail("参数 a、b 必须是数字");
        }

        // 3) 执行运算
        double result;
        switch (op) {
            case "add" -> result = a + b;
            case "subtract" -> result = a - b;
            case "multiply" -> result = a * b;
            case "divide" -> {
                if (b == 0) {
                    return CallToolResult.fail("除数不能为 0");
                }
                result = a / b;
            }
            default -> {
                return CallToolResult.fail("不支持的运算类型：" + op
                        + "，仅支持 add/subtract/multiply/divide");
            }
        }

        // 4) 返回结果（整数结果去掉多余小数）
        String text = (result == Math.floor(result) && !Double.isInfinite(result))
                ? String.valueOf((long) result)
                : String.valueOf(result);
        return CallToolResult.ok("计算结果：" + text);
    }

    /** 把任意数值/字符串安全转成 double */
    private double toDouble(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        return Double.parseDouble(String.valueOf(o).trim());
    }
}