package com.zero.ai.agentstudy.day17codeagent.safety;

import com.zero.ai.agentstudy.day17codeagent.patch.TextPatch;

/**
 * Chapter 14 演示：安全护栏。
 *
 * <p>三道闸门：路径黑名单 → 敏感文件路径 → 危险 API 与体量 → 密钥泄露。</p>
 *
 * @author ZeroAi
 */
public class Chapter14Demo {

    public static void main(String[] args) {
        PatchSafetyChecker checker = PatchSafetyChecker.defaults();
        SecretScanner scanner = new SecretScanner();

        System.out.println("=== [1] 路径黑名单：CI 配置 ===");
        check(checker, TextPatch.unique(".github/workflows/ci.yml",
                "runs-on: ubuntu-latest", "runs-on: ubuntu-latest\n  secrets: inherit"));
        check(checker, TextPatch.unique(".env",
                "OPENAI_API_KEY=old", "OPENAI_API_KEY=new"));

        System.out.println("\n=== [2] 敏感文件：构建配置 (WARN 而非 REJECT) ===");
        check(checker, TextPatch.unique("pom.xml",
                "<version>1.0</version>", "<version>1.1</version>"));

        System.out.println("\n=== [3] 体量失控：一次删上百行 ===");
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 260; i++) {
            huge.append("    // line ").append(i).append('\n');
        }
        check(checker, new TextPatch("src/main/java/demo/order/Money.java",
                huge.toString(), "    // rewritten\n", null, null));

        System.out.println("\n=== [4] 危险 API ===");
        check(checker, TextPatch.unique("src/main/java/demo/order/OrderService.java",
                "    public boolean isPaid(String orderId) {",
                "    public boolean isPaid(String orderId) {\n"
                        + "        Runtime.getRuntime().exec(\"rm -rf /tmp/x\");"));
        check(checker, TextPatch.unique("src/main/java/demo/order/OrderService.java",
                "    public boolean isPaid(String orderId) {",
                "    public boolean isPaid(String orderId) {\n"
                        + "        String key = System.getenv(\"OPENAI_API_KEY\");"));

        System.out.println("\n=== [5] 正常业务改动应当放行 ===");
        check(checker, TextPatch.unique("src/main/java/demo/order/Money.java",
                "    public Money add(Money other) {",
                "    public Money add(Money other) {"));

        System.out.println("\n=== [6] 密钥泄露扫描 ===");
        String poisoned = """
                public class AiConfig {
                    private String key = "sk-proj-AbCdEfGhIjKlMnOpQrStUvWx";
                    private String aws = "AKIAIOSFODNN7EXAMPLE";
                    private String pwd = "SuperSecret123!";
                    private String url = "jdbc:mysql://db/p?password=hunter2";
                }
                """;
        var findings = scanner.scan("src/main/java/demo/order/AiConfig.java", poisoned);
        findings.forEach(f -> System.out.println("  " + f));
        System.out.println("  脱敏演示：raw = sk-proj-AbCdEfGhIjKlMnOpQrStUvWx → "
                + SecretScanner.redact("sk-proj-AbCdEfGhIjKlMnOpQrStUvWx"));
        System.out.println("  → 日志里绝不能打印原文明文，否则等于把刚拦下的密钥又泄露一遍");

        String clean = """
                public class AiConfig {
                    private String key = System.getenv("OPENAI_API_KEY");
                }
                """;
        System.out.println("  干净代码命中数 = "
                + scanner.scan("AiConfig.java", clean).size()
                + "（用环境变量注入才是正确姿势）");

        System.out.println("\n=== [7] 规则清单 ===");
        System.out.println("  禁止路径 = " + PatchSafetyChecker.forbiddenPaths());
        System.out.println("  高危 API = " + PatchSafetyChecker.dangerousApiRules());
        System.out.println("  密钥规则 = " + SecretScanner.ruleNames());
    }

    private static void check(PatchSafetyChecker checker, TextPatch patch) {
        PatchSafetyChecker.SafetyVerdict verdict = checker.check(patch);
        System.out.printf("  [%s] %s%n", verdict.verdict(), patch.filePath());
        verdict.reasons().forEach(r -> System.out.println("        理由：" + r));
        verdict.hints().forEach(h -> System.out.println("        建议：" + h));
    }
}
