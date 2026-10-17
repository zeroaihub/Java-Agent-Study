package com.zero.ai.agentstudy.day17codeagent.analysis;

import com.zero.ai.agentstudy.day17codeagent.core.common.CodeException;
import com.zero.ai.agentstudy.day17codeagent.core.common.CodeErrorCode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 仓库地图（RepoMap）：把整个仓库压缩成 LLM 能一口气读完的「目录 + 签名」索引。
 *
 * <h3>它解决什么问题</h3>
 * <p>把 300 个文件全塞进上下文是不可能的。但只给 LLM 一个文件名列表也不够——
 * 它不知道该读哪个。RepoMap 是两者的折中：<b>给出「签名级」信息</b>，
 * 让模型知道「有这个东西在哪」，但不占用「具体内容」的预算。</p>
 *
 * <p>类比：RepoMap 相当于给模型一张<b>图书馆索引卡</b>，而不是把全书搬来。</p>
 *
 * <p><b>体量约束</b>：一个真实中台仓库（~10 万行）的完整 RepoMap 约 6~8k token，
 * 这是可以放进上下文的；而源码本身是 500k token 量级。这就是它存在的全部理由。</p>
 *
 * @author ZeroAi
 */
public class RepoMap {

    private final Map<String, FileSummary> summaries = new TreeMap<>();
    private final DependencyGraph graph;

    private RepoMap(DependencyGraph graph) {
        this.graph = graph;
    }

    /** 由「路径 → 源码」构建仓库地图。 */
    public static RepoMap build(Map<String, String> sources) {
        SymbolTable table = SymbolTable.build(sources);
        DependencyGraph graph = DependencyGraph.build(sources);
        RepoMap map = new RepoMap(graph);

        for (String path : table.files()) {
            List<JavaSymbol> symbols = table.ofFile(path);
            JavaSymbol topType = symbols.stream()
                    .filter(s -> s.kind().isType())
                    .findFirst()
                    .orElse(null);
            // 成员 = 非顶层类型的符号；嵌套类型（如 record Order）也算成员，否则接口会显示成空壳
            List<JavaSymbol> members = symbols.stream()
                    .filter(s -> !s.kind().isType()
                            || (topType != null && !s.name().equals(topType.name())))
                    .sorted(Comparator.comparingInt(JavaSymbol::line))
                    .toList();
            int lines = linesOf(sources.get(path));
            map.summaries.put(path, new FileSummary(
                    path, lines, topType, List.copyOf(members),
                    Set.copyOf(graph.dependenciesOf(path).stream()
                            .map(RepoMap::shortName).toList())));
        }
        return map;
    }

    public static RepoMap of(com.zero.ai.agentstudy.day17codeagent.repo.RepoSnapshot snapshot) {
        Map<String, String> sources = new LinkedHashMap<>();
        for (String path : snapshot.paths()) {
            sources.put(path, snapshot.get(path));
        }
        return build(sources);
    }

    /**
     * 渲染成文本（这就是要拼进提示词的内容）。
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        for (FileSummary summary : summaries.values()) {
            sb.append(renderFile(summary)).append('\n');
        }
        return sb.toString();
    }

    /** 只渲染指定文件。 */
    public String render(List<String> paths) {
        StringBuilder sb = new StringBuilder();
        for (String path : paths) {
            FileSummary summary = summaries.get(path);
            if (summary != null) {
                sb.append(renderFile(summary)).append('\n');
            }
        }
        return sb.toString();
    }

    private String renderFile(FileSummary summary) {
        StringBuilder sb = new StringBuilder();
        sb.append(summary.path())
                .append(" (").append(summary.lineCount()).append(" 行)");
        if (summary.dependencies() != null && !summary.dependencies().isEmpty()) {
            sb.append(" [依赖: ").append(String.join(", ", summary.dependencies())).append(']');
        }
        sb.append('\n');
        if (summary.topType() != null) {
            sb.append("  ").append(summary.topType().modifiersText().isBlank() ? ""
                            : summary.topType().modifiersText() + " ")
                    .append(summary.topType().kind().display()).append(' ')
                    .append(summary.topType().name()).append('\n');
        }
        for (JavaSymbol member : summary.members()) {
            String head = member.isPublic() ? "+" : "-";
            sb.append("    ").append(head).append(' ');
            if (member.kind().isType()) {
                // 嵌套类型
                sb.append(member.kind().display()).append(' ').append(member.name())
                        .append("  @L").append(member.line()).append('\n');
            } else if (member.kind() == SymbolKind.FIELD) {
                // 字段形如「类型 名」，没有括号
                sb.append(member.name()).append(" :").append(typeOf(member.signature()))
                        .append("  @L").append(member.line()).append('\n');
            } else {
                sb.append(member.name()).append('(')
                        .append(paramsOf(member.signature())).append(')')
                        .append(" :").append(returnTypeOf(member.signature()))
                        .append("  @L").append(member.line()).append('\n');
            }
        }
        return sb.toString();
    }

    private static String paramsOf(String signature) {
        int open = signature.indexOf('(');
        int close = signature.lastIndexOf(')');
        return (open >= 0 && close > open) ? signature.substring(open + 1, close) : "";
    }

    /**
     * 从方法签名里取<b>返回类型</b>。
     *
     * <p>签名形如 {@code Money of(long)}：括号前是「返回类型 + 方法名」，
     * 因此最后一个空格<b>之后是方法名、之前才是返回类型</b>。
     * 早先版本错误地取了空格之后的部分，于是 RepoMap 里出现了
     * {@code of(long) :of} 这种「返回类型等于函数名」的笑话。</p>
     */
    private static String returnTypeOf(String signature) {
        int open = signature.indexOf('(');
        if (open < 0) {
            return "void";
        }
        String head = signature.substring(0, open).trim();
        int space = head.lastIndexOf(' ');
        // 构造器签名不含返回类型，head 里只有方法名
        return space < 0 ? "ctor" : head.substring(0, space);
    }

    /** 字段签名 {@code BigDecimal amount} 的类型部分。 */
    private static String typeOf(String signature) {
        int space = signature.lastIndexOf(' ');
        return space < 0 ? signature : signature.substring(0, space);
    }

    private static String shortName(String path) {
        int slash = path.lastIndexOf('/');
        String file = slash >= 0 ? path.substring(slash + 1) : path;
        return file.replace(".java", "");
    }

    private static int linesOf(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    public Map<String, FileSummary> summaries() {
        return Map.copyOf(summaries);
    }

    public DependencyGraph graph() {
        return graph;
    }

    public int fileCount() {
        return summaries.size();
    }

    public int totalLines() {
        return summaries.values().stream().mapToInt(FileSummary::lineCount).sum();
    }

    /** 估算 token 数（中文按 1.5 字符/token 粗算，属于工程量级估算）。 */
    public int estimatedTokens() {
        return (int) (render().length() * 0.6);
    }

    /** 断言地图非空——后续章节依赖它，空地图说明仓库读错了。 */
    public void requireNotEmpty() {
        if (summaries.isEmpty()) {
            throw new CodeException(CodeErrorCode.REPO_MAP_EMPTY,
                    "仓库地图为空：没有任何 Java 文件被解析，请检查仓库路径");
        }
    }

    /**
     * 单个文件的摘要。
     *
     * @param path         相对路径
     * @param lineCount    行数
     * @param topType      顶层类型符号（可为 null，表示非 Java 文件）
     * @param members      成员符号列表，按行号排序
     * @param dependencies 依赖的类型名（已去掉后缀）
     */
    public record FileSummary(
            String path,
            int lineCount,
            JavaSymbol topType,
            List<JavaSymbol> members,
            Set<String> dependencies
    ) {
        /** 取所有成员名，供关键词匹配使用。 */
        public List<String> memberNames() {
            List<String> names = new ArrayList<>();
            members.forEach(m -> names.add(m.name()));
            return List.copyOf(names);
        }
    }
}
