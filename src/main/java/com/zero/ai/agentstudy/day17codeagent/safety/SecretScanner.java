package com.zero.ai.agentstudy.day17codeagent.safety;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 密钥泄露扫描器：检查代码文本里是否出现了疑似凭证。
 *
 * <h3>为什么必须在写入前扫一遍</h3>
 * <p>LLM 训练语料里有大量「带密钥的示例代码」。让它写一个调用 OpenAI 的例子，
 * 它有相当概率顺手写出 {@code apiKey = "sk-proj-..."} 这种真实格式的字符串。
 * 一旦这类东西进了仓库 —— 尤其进了公开仓库 —— 就是一次安全事故。</p>
 *
 * <p>更隐蔽的是：这类代码<b>能通过编译、能通过测试</b>，代码评审也要盯得很仔细才会发现。
 * 所以必须把它自动化。</p>
 *
 * <p><b>诚实交代</b>：正则检测必然有误报（例如测试 fixture 里的假密钥）与漏报
 * （高度定制的密钥格式）。生产请配合 gitleaks / truffleHog 与 CI 门禁。</p>
 *
 * @author ZeroAi
 */
public class SecretScanner {

    /** 规则表：名称 → 正则。 */
    private static final Map<String, Pattern> RULES = new LinkedHashMap<>();

    static {
        RULES.put("OpenAI API Key", Pattern.compile("sk-[A-Za-z0-9_-]{16,}"));
        RULES.put("AWS Access Key", Pattern.compile("AKIA[0-9A-Z]{16}"));
        RULES.put("GitHub Token", Pattern.compile("gh[pousr]_[A-Za-z0-9]{20,}"));
        RULES.put("Slack Token", Pattern.compile("xox[baprs]-[A-Za-z0-9-]{10,}"));
        RULES.put("私钥", Pattern.compile("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"));
        RULES.put("通用密码赋值", Pattern.compile(
                "(?i)\\b(password|passwd|secret|api[_-]?key|access[_-]?token)\\s*=\\s*\"[^\"]{8,}\""));
        RULES.put("JDBC 密码", Pattern.compile(
                "(?i)jdbc:[a-z0-9]+://[^\\s]*password=[^\\s&\"';]{4,}"));
    }

    /** 疑似泄露项。 */
    public record Finding(String rule, String file, int line, String redactedEvidence) {
        @Override
        public String toString() {
            return rule + " @ " + file + ":" + line + " → " + redactedEvidence;
        }
    }

    /** 扫描一段文本。 */
    public List<Finding> scan(String filePath, String content) {
        List<Finding> findings = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return findings;
        }
        String[] lines = content.split("\n", -1);
        for (Map.Entry<String, Pattern> rule : RULES.entrySet()) {
            for (int i = 0; i < lines.length; i++) {
                Matcher m = rule.getValue().matcher(lines[i]);
                if (m.find()) {
                    findings.add(new Finding(rule.getKey(), filePath, i + 1, redact(m.group())));
                }
            }
        }
        return List.copyOf(findings);
    }

    /**
     * 脱敏：只保留前 4 个字符，其余打码。
     *
     * <p><b>这一步绝对不能省</b>：把命中的原文完整打印进日志，等于把刚刚拦下来的密钥又泄露一遍。
     * 而且日志通常比代码更容易被访问。</p>
     */
    public static String redact(String evidence) {
        if (evidence == null) {
            return "";
        }
        if (evidence.length() <= 4) {
            return "*".repeat(evidence.length());
        }
        return evidence.substring(0, 4) + "*".repeat(Math.min(12, evidence.length() - 4));
    }

    public static List<String> ruleNames() {
        return List.copyOf(RULES.keySet());
    }
}
