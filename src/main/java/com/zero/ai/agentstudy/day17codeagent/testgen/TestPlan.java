package com.zero.ai.agentstudy.day17codeagent.testgen;

import com.zero.ai.agentstudy.day17codeagent.analysis.JavaSymbol;

import java.util.List;

/**
 * 测试生成计划。
 *
 * <p>为什么 planning 与 rendering 要分开：先决定「测什么」，再决定「怎么写成源码」。
 * 这样：</p>
 * <ul>
 *     <li>可以只生成部分用例（按优先级 / 按本次改动的符号）；</li>
 *     <li>可以统计覆盖率缺口（目标符号 vs 已测符号）；</li>
 *     <li>换测试框架时只需重写渲染部分。</li>
 * </ul>
 *
 * @param testClassName 生成的测试类名
 * @param packageName   包名（必须与被测类一致，才能访问包可见成员）
 * @param targetClass   被测类名
 * @param cases         用例列表
 * @author ZeroAi
 */
public record TestPlan(
        String testClassName,
        String packageName,
        String targetClass,
        List<Case> cases
) {

    /**
     * 一个用例。
     *
     * @param targetSymbol 被测符号（方法签名）
     * @param name         用例名
     * @param description  用例要验证的行为（自然语言，会写进注释）
     * @param kind         用例类型
     */
    public record Case(JavaSymbol targetSymbol, String name, String description, Kind kind) {
        public enum Kind {
            /** 正常路径。 */
            HAPPY_PATH,
            /** 边界条件：0 / 空 / 极大值。 */
            BOUNDARY,
            /** 异常路径。 */
            EXCEPTION;

            public String display() {
                return switch (this) {
                    case HAPPY_PATH -> "正常路径";
                    case BOUNDARY -> "边界条件";
                    case EXCEPTION -> "异常路径";
                };
            }
        }
    }

    public int size() {
        return cases.size();
    }

    /** 覆盖了哪些方法名（去重）。 */
    public List<String> coveredMethods() {
        return cases.stream()
                .map(c -> c.targetSymbol() == null ? "(unknown)" : c.targetSymbol().name())
                .distinct().toList();
    }

    @Override
    public String toString() {
        return "TestPlan{" + testClassName + ", 被测=" + targetClass
                + ", 用例 " + cases.size() + " 个，覆盖方法 " + coveredMethods() + "}";
    }
}
