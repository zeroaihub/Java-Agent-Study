package com.zero.ai.agentstudy.day3funcall.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 计算器工具（第五章）
 *
 * 教学要点：
 * 1. LLM 不擅长精确计算（它是"猜"下一个 token），把计算交给 Tool 才 100% 正确。
 * 2. 一个 Tool 单一职责：这里只做四则运算。
 * 3. 参数用枚举描述（op），并做非法值兜底，返回结构化 JSON。
 *
 * @author ZeroAi
 */
@Slf4j
@Component
public class CalculatorTool03 {

    /**
     * 四则运算。
     *
     * @param a  第一个操作数
     * @param b  第二个操作数
     * @param op 运算符：add/subtract/multiply/divide
     * @return 结构化 JSON 结果
     */
    @Tool(description = "对两个数字做四则运算（加减乘除）。当用户需要精确计算、算数学题时使用")
    public String calculate(
            @ToolParam(description = "第一个操作数") double a,
            @ToolParam(description = "第二个操作数") double b,
            @ToolParam(description = "运算符，枚举：add(加)/subtract(减)/multiply(乘)/divide(除)") String op) {

        log.info("[CalculatorTool] 被调用, a={}, b={}, op={}", a, b, op);

        if (op == null) {
            return "{\"code\":\"INVALID_PARAM\",\"message\":\"运算符不能为空\"}";
        }

        // 归一化：兼容 LLM 可能传入的多种写法
        String o = normalize(op);
        double result;
        switch (o) {
            case "add":
                result = a + b;
                break;
            case "subtract":
                result = a - b;
                break;
            case "multiply":
                result = a * b;
                break;
            case "divide":
                if (b == 0) {
                    return "{\"code\":\"DIVIDE_BY_ZERO\",\"message\":\"除数不能为0\"}";
                }
                result = a / b;
                break;
            default:
                return "{\"code\":\"UNSUPPORTED\",\"message\":\"不支持的运算符: " + op + "\"}";
        }

        return String.format("{\"a\":%s,\"b\":%s,\"op\":\"%s\",\"result\":%s}", a, b, o, result);
    }

    /** 兼容多种运算符写法，统一成标准枚举 */
    private String normalize(String op) {
        String s = op.trim().toLowerCase();
        if (s.equals("+") || s.contains("add") || s.contains("加") || s.contains("plus")) return "add";
        if (s.equals("-") || s.contains("sub") || s.contains("减") || s.contains("minus")) return "subtract";
        if (s.equals("*") || s.contains("mul") || s.contains("乘") || s.contains("times") || s.equals("x")) return "multiply";
        if (s.equals("/") || s.contains("div") || s.contains("除")) return "divide";
        return s;
    }

    /**
     * 求某个数的平方根。
     *
     * @param a  第一个操作数
     * @return 结构化 JSON 结果
     */
    @Tool(description = "对一个数字计算平方根。当用户需要精确某个数的平方根时使用")
    public String square(@ToolParam(description = "操作数") double a) {

        log.info("[square] 被调用, a={}", a);

        return "{\"result\": " + Math.sqrt(a) + "}";
    }
}