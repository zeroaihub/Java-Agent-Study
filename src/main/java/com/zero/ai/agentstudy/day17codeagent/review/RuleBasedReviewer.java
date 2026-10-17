package com.zero.ai.agentstudy.day17codeagent.review;

import com.zero.ai.agentstudy.day17codeagent.patch.UnifiedDiff;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 规则式代码评审器：只对<b>本次新增的行</b>提意见。
 *
 * <h3>为什么只评审增量</h3>
 * <p>评审全量文件会带来两个问题：</p>
 * <ol>
 *     <li><b>噪声</b>：一个 800 行的老文件里可能有 30 处历史遗留问题，
 *         全打出来没人看，还会淹没本次真正的改动；</li>
 *     <li><b>不可执行</b>：「建议把整个文件的 TODO 都修一下」是一个不可能被接受的建议。</li>
 * </ol>
 * <p>只评审 diff 里的 {@code +} 行，意见才有可操作性。这也是 GitHub / GitLab
 * 内联评论的默认行为。</p>
 *
 * @author ZeroAi
 */
public class RuleBasedReviewer {

    /** 规则表：规则名 → 检查器。 */
    private final Map<String, PatternRule> rules = new LinkedHashMap<>();

    public RuleBasedReviewer() {
        registerDefaults();
    }

    private void registerDefaults() {
        rules.put("空 catch 块", new PatternRule(
                // 真实代码里 catch 块通常是多行写法：`catch (...) {` 独占一行，
                // 若这里只匹配单行 `catch (...) {}` 就会漏掉绝大多数情况。
                Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}"
                        + "|catch\\s*\\([^)]*\\)\\s*\\{\\s*$"),
                ReviewComment.Severity.BLOCKER,
                "吞掉异常会让线上问题彻底失去线索",
                "至少要记录日志：catch (XxxException e) { log.warn(\"...\", e); }",
                false));

        rules.put("printStackTrace", new PatternRule(
                Pattern.compile("printStackTrace\\(\\)"),
                ReviewComment.Severity.MAJOR,
                "直接打印堆栈会绕过日志系统，无法被采集与告警",
                "改用 logger.error(\"...\", e)"));

        rules.put("System.out 残留", new PatternRule(
                Pattern.compile("System\\.out\\.print"),
                ReviewComment.Severity.MINOR,
                "调试输出不应进入生产代码",
                "删除，或改为结构化日志"));

        rules.put("魔法数字", new PatternRule(
                Pattern.compile("(?<![\\w.$])\\d{3,}(?![\\w.$L])"),
                ReviewComment.Severity.MINOR,
                "可读数字应当抽取为有名字的常量",
                "提为 private static final 常量并命名"));

        rules.put("超长行", new PatternRule(
                Pattern.compile("^\\s*\\S.{119,}$"),
                ReviewComment.Severity.MINOR,
                "单行超过 120 字符，影响 code review 时的可读性",
                "按语义换行"));

        rules.put("TODO/FIXME", new PatternRule(
                Pattern.compile("TODO|FIXME|XXX"),
                ReviewComment.Severity.INFO,
                "遗留待办标记",
                "确认是否需要落成正式任务单",
                true));

        rules.put("可变静态字段", new PatternRule(
                Pattern.compile("public\\s+static\\s+(?!final)\\w+\\s+\\w+\\s*="),
                ReviewComment.Severity.BLOCKER,
                "公开可变静态字段在多线程下是竞态温床，且任何代码都能改",
                "改为 private static final，或提供受控的访问方法"));
    }

    /**
     * 评审一次改动。
     *
     * @param filePath 文件相对路径
     * @param before   改动前内容
     * @param after    改动后内容
     */
    public List<ReviewComment> review(String filePath, String before, String after) {
        List<ReviewComment> comments = new ArrayList<>();
        List<UnifiedDiff.DiffLine> lines = UnifiedDiff.diff(before, after);

        // 建立行号映射：变更前的行号 / 变更后的行号
        int newLineNo = 0;
        for (UnifiedDiff.DiffLine line : lines) {
            if (line.type() != UnifiedDiff.DiffLine.Type.REMOVE) {
                newLineNo++;
            }
            if (line.type() != UnifiedDiff.DiffLine.Type.ADD) {
                continue;
            }
            String content = line.content();
            String trimmed = content.strip();
            boolean isComment = trimmed.startsWith("//") || trimmed.startsWith("*");
            if (trimmed.isEmpty() || (isComment && rules.values().stream()
                    .noneMatch(r -> r.appliesToComments()))) {
                continue;
            }
            for (Map.Entry<String, PatternRule> entry : rules.entrySet()) {
                PatternRule rule = entry.getValue();
                if (isComment && !rule.appliesToComments()) {
                    continue; // 注释行只跑显式声明过的规则，避免误报
                }
                if (rule.pattern().matcher(content).find()) {
                    comments.add(new ReviewComment(
                            rule.severity(), entry.getKey(), filePath, newLineNo,
                            rule.message() + "：" + trimmed,
                            rule.suggestion()));
                }
            }
        }
        return List.copyOf(comments);
    }

    public List<String> ruleNames() {
        return List.copyOf(rules.keySet());
    }

    /** 单条规则。 */
    private record PatternRule(Pattern pattern, ReviewComment.Severity severity,
                               String message, String suggestion, boolean appliesToComments) {

        /** 便捷构造器：默认不作用于注释行。 */
        PatternRule(Pattern pattern, ReviewComment.Severity severity,
                    String message, String suggestion) {
            this(pattern, severity, message, suggestion, false);
        }
    }

    /**
     * 评审汇总。
     *
     * @param comments 全部意见
     */
    public record ReviewSummary(List<ReviewComment> comments) {
        public boolean hasBlocker() {
            return comments.stream().anyMatch(ReviewComment::isBlocking);
        }

        public long count(ReviewComment.Severity severity) {
            return comments.stream().filter(c -> c.severity() == severity).count();
        }

        @Override
        public String toString() {
            return String.format("ReviewSummary{共 %d 条，必须修改 %d，建议 %d，可选 %d}",
                    comments.size(), count(ReviewComment.Severity.BLOCKER),
                    count(ReviewComment.Severity.MAJOR),
                    count(ReviewComment.Severity.MINOR));
        }
    }
}
