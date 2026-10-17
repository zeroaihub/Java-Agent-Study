package com.zero.ai.agentstudy.day17codeagent.analysis;

import com.zero.ai.agentstudy.day17codeagent.repo.SampleRepository;
import com.zero.ai.agentstudy.day17codeagent.repo.VirtualFileSystem;

import java.util.Map;

/**
 * Chapter 05 演示：静态分析与仓库地图。
 *
 * <p>运行可见：</p>
 * <ol>
 *     <li>零依赖解析器能从样例仓库里提取出类、方法、字段、构造器；</li>
 *     <li><b>注释与字符串里的「假代码」不会污染符号表</b>（这是 naive 正则最常跪的地方）；</li>
 *     <li>依赖图算出反向依赖与影响面；</li>
 *     <li>RepoMap 把仓库压缩成一页索引卡。</li>
 * </ol>
 *
 * @author ZeroAi
 */
public class Chapter05Demo {

    public static void main(String[] args) {
        VirtualFileSystem vfs = VirtualFileSystem.temp("analysis-demo");
        vfs.writeAll(SampleRepository.files());

        // ---- 1. 解析单个文件 ----
        System.out.println("=== [1] 解析 Money.java ===");
        String moneySource = vfs.read(SampleRepository.MONEY).content();
        LightweightJavaParser.SourceInfo info =
                LightweightJavaParser.parse(SampleRepository.MONEY, moneySource);
        System.out.println("  package = " + info.packageName());
        System.out.println("  imports = " + info.imports());
        System.out.println("  符号清单：");
        info.symbols().forEach(s -> System.out.printf("    %-11s %-14s %-28s L%d%n",
                s.kind().display(), s.name(), s.signature(), s.line()));

        // ---- 2. 注释/字符串污染 ----
        System.out.println("\n=== [2] 注释与字符串里的假代码 ===");
        String tricky = """
                package demo.trap;

                public class RealClass {
                    // public class CommentService {
                    //     void hidden() { }
                    private String tip = "public class StringService { void ghost() { } }";
                    private char quote = '{';

                    public void real() {
                        String s = "/* 假装这里是注释 */";
                        System.out.println("// 这也不是注释");
                    }
                }
                """;
        LightweightJavaParser.SourceInfo trapInfo = LightweightJavaParser.parse("Trap.java", tricky);
        System.out.println("  解析出的类型级符号：");
        trapInfo.symbols().stream().filter(s -> s.kind().isType())
                .forEach(s -> System.out.println("    " + s.signature()));
        System.out.println("  解析出的方法：");
        trapInfo.symbols().stream().filter(s -> s.kind() == SymbolKind.METHOD)
                .forEach(s -> System.out.println("    " + s.signature()));
        System.out.println("  → 若不做清洗，这里会多出 CommentService / StringService / ghost 三个幽灵符号");

        System.out.println("\n  【清洗后的文本】（保留换行、内容变空格，因此行号不偏移）");
        String stripped = LightweightJavaParser.stripCommentsAndStrings(tricky);
        stripped.lines().limit(6).forEach(l -> System.out.println("    |" + l + "|"));

        // ---- 3. 符号表 ----
        System.out.println("\n=== [3] 符号表：精确查 + 模糊查 ===");
        SymbolTable table = SymbolTable.build(vfs.snapshot().files());
        System.out.println("  文件数 = " + table.files().size() + "  符号总数 = " + table.symbolCount());
        System.out.println("  精确查 \"discountFor\" → " + table.find("discountFor"));
        System.out.println("  模糊查 \"Order\" → ");
        table.search("Order").forEach(s -> System.out.println("      " + s));
        System.out.println("  模糊查 \"Money\" → ");
        table.search("Money").forEach(s -> System.out.println("      " + s));

        // ---- 4. 依赖图 ----
        System.out.println("\n=== [4] 依赖图：谁引用了谁 ===");
        DependencyGraph graph = DependencyGraph.build(vfs.snapshot().files());
        System.out.println("  节点数 = " + graph.nodeCount() + "  边数 = " + graph.edgeCount());
        for (String file : graph.edges().keySet()) {
            System.out.printf("    %-46s 依赖 %s%n", shortName(file), graph.dependenciesOf(file));
        }
        System.out.println("  反向：用到 Money 的文件 = " + graph.dependentsOf(SampleRepository.MONEY));
        System.out.println("  是否存在环 = " + graph.hasCycle());

        System.out.println("\n  【影响面】改动 Money.java 时需要一并检查：");
        graph.impactSet(java.util.List.of(SampleRepository.MONEY))
                .forEach(p -> System.out.println("      " + p));

        // ---- 5. RepoMap ----
        System.out.println("\n=== [5] RepoMap：给 LLM 的一页索引卡 ===");
        RepoMap repoMap = RepoMap.build(vfs.snapshot().files());
        repoMap.requireNotEmpty();
        System.out.println(repoMap.render());
        System.out.println("  文件数 = " + repoMap.fileCount()
                + "  总行数 = " + repoMap.totalLines()
                + "  估算 token ≈ " + repoMap.estimatedTokens());

        Map<String, RepoMap.FileSummary> summaries = repoMap.summaries();
        System.out.println("\n  RepoMap 只占 " + repoMap.estimatedTokens()
                + " token，而五个文件原文共 " + repoMap.totalLines()
                + " 行 —— 这就是「先给索引卡，再按需取原文」的价值");
    }

    private static String shortName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
