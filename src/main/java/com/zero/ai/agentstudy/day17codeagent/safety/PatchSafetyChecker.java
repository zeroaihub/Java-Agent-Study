package com.zero.ai.agentstudy.day17codeagent.safety;

import com.zero.ai.agentstudy.day17codeagent.patch.TextPatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 补丁安全审查：在补丁<b>落盘之前</b>判断它是否碰了不该碰的东西。
 *
 * <h3>为什么要拦这里</h3>
 * <p>代码评审发生在落盘之后，而有些东西一旦被写进去，评审时已经晚了：</p>
 * <ul>
 *     <li><b>CI 配置</b>（{@code .github/workflows/*.yml}）：改一行即可让流水线把密钥打出去；</li>
 *     <li><b>构建文件</b>（{@code pom.xml} / {@code build.gradle}）：加一个依赖就是一次供应链攻击；</li>
 *     <li><b>密钥文件</b>（{@code .env}、{@code application-prod.yml}）；</li>
 *     <li><b>体量失控</b>：一个「改个小 bug」的需求，补丁却动辄删除几百行。</li>
 * </ul>
 *
 * <p>所以要在写入前加一道闸门，把上述情况变成<b>可判定的规则</b>。</p>
 *
 * @author ZeroAi
 */
public class PatchSafetyChecker {

    /** 禁止修改的路径（前缀匹配）。 */
    private static final Set<String> FORBIDDEN_PATHS = Set.of(
            ".github/workflows/", ".gitlab-ci", "Jenkinsfile", ".env",
            "src/main/resources/application-prod");

    /** 需要额外警惕的路径（允许改，但必须标记）。 */
    private static final Set<String> SENSITIVE_PATHS = Set.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle",
            "Dockerfile", "src/main/resources/application.yml", "src/test/");

    /** 危险 API 正则。 */
    private static final Map<String, Pattern> DANGEROUS_API = new LinkedHashMap<>();

    static {
        DANGEROUS_API.put("执行系统命令", Pattern.compile(
                "Runtime\\.getRuntime\\(\\)\\.exec|new ProcessBuilder\\(|System\\.exit\\("));
        DANGEROUS_API.put("读取环境变量", Pattern.compile("System\\.getenv\\("));
        DANGEROUS_API.put("任意文件删除", Pattern.compile(
                "Files\\.deleteIfExists|Files\\.delete\\(|File\\(\\)\\.delete"));
        DANGEROUS_API.put("网络访问", Pattern.compile(
                "new URL\\(|HttpURLConnection|Socket\\(|HttpClient\\.new"));
        DANGEROUS_API.put("反射调用", Pattern.compile(
                "Class\\.forName\\(|getDeclaredMethod\\(|setAccessible\\(true\\)"));
    }

    /** 单次补丁允许的最大删除行数。 */
    private final int maxRemovedLines;

    public PatchSafetyChecker(int maxRemovedLines) {
        this.maxRemovedLines = maxRemovedLines;
    }

    public static PatchSafetyChecker defaults() {
        return new PatchSafetyChecker(200);
    }

    /** 审查结果三档：PASS（放行）/ WARN（放行但需人工复核）/ REJECT（拒绝落地）。 */
    public enum Verdict { PASS, WARN, REJECT }

    /**
     * 审查结果。
     *
     * @param verdict 判定
     * @param reasons 判定理由
     * @param hints   给模型的修正建议
     */
    public record SafetyVerdict(Verdict verdict, List<String> reasons, List<String> hints) {
        public boolean rejected() {
            return verdict == Verdict.REJECT;
        }

        public boolean passed() {
            return verdict == Verdict.PASS;
        }

        @Override
        public String toString() {
            return verdict + " " + (reasons.isEmpty() ? "" : String.join("；", reasons));
        }
    }

    /** 审查一个补丁。 */
    public SafetyVerdict check(TextPatch patch) {
        List<String> reasons = new ArrayList<>();
        List<String> hints = new ArrayList<>();
        Verdict verdict = Verdict.PASS;

        String path = patch.filePath() == null ? "" : patch.filePath().replace('\\', '/');

        // ---- 1. 路径黑名单 ----
        for (String forbidden : FORBIDDEN_PATHS) {
            if (path.startsWith(forbidden) || path.equals(forbidden)) {
                reasons.add("禁止修改受保护路径：" + path);
                hints.add("CI 配置与密钥文件不允许由 Agent 修改，请提交人工处理");
                verdict = Verdict.REJECT;
            }
        }
        // ---- 2. 敏感路径 ----
        for (String sensitive : SENSITIVE_PATHS) {
            if (verdict != Verdict.REJECT && (path.startsWith(sensitive) || path.endsWith(sensitive))) {
                reasons.add("修改了敏感文件：" + path);
                hints.add("此项变更需要人工重点复核（构建配置变更会影响整个工程的依赖）");
                verdict = Verdict.WARN;
            }
        }
        // ---- 3. 体量失控 ----
        int removed = patch.stats().removedLines();
        if (removed > maxRemovedLines) {
            reasons.add("删除行数 " + removed + " 超过上限 " + maxRemovedLines);
            hints.add("请拆分为多个更小的补丁，或确认删除确实是需求的一部分");
            verdict = Verdict.REJECT;
        }
        // ---- 4. 危险 API ----
        String addedText = patch.replace() == null ? "" : patch.replace();
        for (Map.Entry<String, Pattern> api : DANGEROUS_API.entrySet()) {
            if (api.getValue().matcher(addedText).find()) {
                reasons.add("新增代码使用了高危 API：" + api.getKey());
                hints.add("如确有必要，请在评审时说明理由；否则应改用受控的替代方案");
                if (verdict == Verdict.PASS) {
                    verdict = Verdict.WARN;
                }
            }
        }
        return new SafetyVerdict(verdict, List.copyOf(reasons), List.copyOf(hints));
    }

    public static Set<String> forbiddenPaths() {
        return Set.copyOf(FORBIDDEN_PATHS);
    }

    public static List<String> dangerousApiRules() {
        return List.copyOf(DANGEROUS_API.keySet());
    }
}
