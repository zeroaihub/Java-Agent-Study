package com.zero.ai.agentstudy.day17codeagent.testgen;

import com.zero.ai.agentstudy.day17codeagent.analysis.JavaSymbol;
import com.zero.ai.agentstudy.day17codeagent.analysis.LightweightJavaParser;
import com.zero.ai.agentstudy.day17codeagent.analysis.SymbolKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试生成器：依据类签名<b>自动产出可执行测试</b>。
 *
 * <h3>为什么 Code Agent 必须会写测试</h3>
 * <p>把「生成的代码能用」的责任全部推给人工 Reviewer，是 Code Agent 最大的信任成本来源。
 * 如果 Agent 每次改动都能同时附上一份跑得起来、能抓住回归的测试，
 * 人的角色就从「逐行检查它写没写错」变成「判断这些测试覆盖了关键场景没有」——
 * 后者的成本是前者的十分之一。</p>
 *
 * <h3>本实现的策略</h3>
 * <ol>
 *     <li>用 {@link LightweightJavaParser} 拿到类的 public 方法；</li>
 *     <li>为每个方法生成正常路径用例；对数值入参的方法补边界用例；对抛异常的方法补异常用例；</li>
 *     <li>渲染成「带 main + assert」的测试类（零依赖，能被 {@code TestVerifier} 直接跑起来）。</li>
 * </ol>
 *
 * <p><b>诚实交代</b>：这里的断言是<b>结构化的占位</b>（能编译、能跑、能演示红绿循环），
 * 并不是真正的语义断言。生产应当：用 LLM 生成真实断言 → 编译 → 跑 → 失败就回到自修复循环。
 * 这套管道在本章已经打通，替换断言生成策略即可。</p>
 *
 * @author ZeroAi
 */
public class TestGenerator {

    /** 由源码生成测试计划。 */
    public TestPlan plan(String packageName, String className, String source) {
        LightweightJavaParser.SourceInfo info =
                LightweightJavaParser.parse(className + ".java", source);

        List<TestPlan.Case> cases = new ArrayList<>();
        // toString/hashCode/equals 这类通用方法不必生成测试
        List<String> skipped = List.of("toString", "hashCode", "equals");

        for (JavaSymbol symbol : info.symbols()) {
            if (symbol.kind() != SymbolKind.METHOD || !symbol.isPublic()) {
                continue;
            }
            if (skipped.contains(symbol.name())) {
                continue;
            }
            cases.add(new TestPlan.Case(
                    symbol,
                    "test" + capitalize(symbol.name()),
                    "验证 " + symbol.signature() + " 的" + TestPlan.Case.Kind.HAPPY_PATH.display(),
                    TestPlan.Case.Kind.HAPPY_PATH));

            if (hasNumericParam(symbol)) {
                cases.add(new TestPlan.Case(
                        symbol,
                        "test" + capitalize(symbol.name()) + "Boundary",
                        "验证 " + symbol.name() + " 在边界值（0 / 极小值）下的行为",
                        TestPlan.Case.Kind.BOUNDARY));
            }
        }
        String idType = guessPrimaryIdType(info.symbols());
        if (idType != null) {
            cases.add(new TestPlan.Case(
                    null, "testInvalidInput",
                    "入参非法时应当抛出明确异常",
                    TestPlan.Case.Kind.EXCEPTION));
        }
        return new TestPlan(className + "Test", packageName, className, List.copyOf(cases));
    }

    /**
     * 渲染成可执行的测试源码。
     *
     * <p>约定：每个用例打印 {@code PASS: <用例名>}，断言失败会抛 AssertionError，
     * 由 {@code TestVerifier} 判定结果。</p>
     */
    public String render(TestPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(plan.packageName()).append(";\n\n");
        sb.append("/**\n * 自动生成的测试类（由 TestGenerator 产出）。\n")
                .append(" * 被测：").append(plan.targetClass())
                .append("  用例：").append(plan.size()).append(" 个\n */\n");
        sb.append("public class ").append(plan.testClassName()).append(" {\n\n");
        sb.append("    public static void main(String[] args) {\n");
        for (TestPlan.Case c : plan.cases()) {
            sb.append("        ").append(c.name()).append("();\n");
        }
        sb.append("        System.out.println(\"ALL DONE\");\n");
        sb.append("    }\n");

        for (TestPlan.Case c : plan.cases()) {
            sb.append('\n');
            sb.append("    /** ").append(c.description()).append("（")
                    .append(c.kind().display()).append("） */\n");
            sb.append("    private static void ").append(c.name()).append("() {\n");
            sb.append("        // TODO(自动生成的占位断言)：请替换为真实的语义断言\n");
            if (c.kind() == TestPlan.Case.Kind.EXCEPTION) {
                sb.append("        // 期望：非法入参时抛出明确异常\n");
                sb.append("        try {\n")
                        .append("            // new ").append(plan.targetClass())
                        .append("(/* 非法入参 */);\n")
                        .append("            // fail(\"应当抛出异常\");\n")
                        .append("        } catch (RuntimeException expected) {\n")
                        .append("            // 符合预期\n")
                        .append("        }\n");
            } else {
                sb.append("        // 被测方法：")
                        .append(c.targetSymbol() == null ? "(无)"
                                : c.targetSymbol().signature()).append('\n');
                sb.append("        // 示例：被测 SoT 结果应当满足预期，例如 assert x != null;\n");
                sb.append("        assert true : \"占位断言，尚未实现真实校验\";\n");
            }
            sb.append("        System.out.println(\"PASS: ").append(c.name()).append("\");\n");
            sb.append("    }\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    /** 便捷方法：一步生成 + 渲染。 */
    public String generate(String packageName, String className, String source) {
        return render(plan(packageName, className, source));
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static boolean hasNumericParam(JavaSymbol symbol) {
        String sig = symbol.signature();
        int open = sig.indexOf('(');
        int close = sig.lastIndexOf(')');
        String params = (open >= 0 && close > open) ? sig.substring(open + 1, close) : "";
        return params.contains("long") || params.contains("int")
                || params.contains("double") || params.contains("short");
    }

    /** 猜测主键入参类型（有构造器说明可能需要构造实例）。 */
    private static String guessPrimaryIdType(List<JavaSymbol> symbols) {
        for (JavaSymbol s : symbols) {
            if (s.kind() == SymbolKind.CONSTRUCTOR && !s.signature().contains("()")) {
                return s.signature();
            }
        }
        return null;
    }

    /** 覆盖情况摘要。 */
    public Map<String, Integer> coverageSummary(String packageName, String className, String source) {
        Map<String, Integer> summary = new LinkedHashMap<>();
        TestPlan plan = plan(packageName, className, source);
        summary.put("用例总数", plan.size());
        summary.put("覆盖方法数", plan.coveredMethods().size());
        summary.put("边界用例", (int) plan.cases().stream()
                .filter(c -> c.kind() == TestPlan.Case.Kind.BOUNDARY).count());
        summary.put("异常用例", (int) plan.cases().stream()
                .filter(c -> c.kind() == TestPlan.Case.Kind.EXCEPTION).count());
        return summary;
    }
}
