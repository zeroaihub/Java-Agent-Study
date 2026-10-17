package com.zero.ai.agentstudy.day17codeagent.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 符号表：整个仓库的「类 / 方法 / 字段 → 位置」倒排索引。
 *
 * <p>Code Agent 拿到一个新仓库时，第一个问题永远是「我想改的东西在哪」。
 * 符号表就是把这个问题从「全文搜索」降维成「查字典」。</p>
 *
 * <p><b>为什么要预建索引而不是每次现搜全文</b>：一次任务里会反复追问同一个符号，
 * 搜一次 800ms vs 查一次 0.1ms，在自修复循环里这点差距会被放大成数量级的浪费。</p>
 *
 * @author ZeroAi
 */
public class SymbolTable {

    private final Map<String, List<JavaSymbol>> byFile = new LinkedHashMap<>();
    private final Map<String, List<JavaSymbol>> byName = new TreeMap<>();

    /** 用一份「路径 → 源码」构建符号表。 */
    public static SymbolTable build(Map<String, String> sources) {
        SymbolTable table = new SymbolTable();
        sources.forEach((path, source) -> {
            if (!path.endsWith(".java")) {
                return;
            }
            LightweightJavaParser.SourceInfo info = LightweightJavaParser.parse(path, source);
            table.register(path, info.symbols());
        });
        return table;
    }

    public void register(String path, List<JavaSymbol> symbols) {
        byFile.put(path, List.copyOf(symbols));
        for (JavaSymbol s : symbols) {
            byName.computeIfAbsent(s.name(), k -> new ArrayList<>()).add(s);
        }
    }

    public List<JavaSymbol> ofFile(String path) {
        return byFile.getOrDefault(path, List.of());
    }

    /** 精确查名。 */
    public List<JavaSymbol> find(String name) {
        return byName.getOrDefault(name, List.of());
    }

    /**
     * 模糊查名（大小写不敏感的子串匹配），找不到精确结果时的兜底。
     *
     * <p>LLM 经常给出「半对」的符号名（{@code OrderSvc} vs {@code OrderService}），
     * 精确匹配失败直接报错会让链路断掉；模糊匹配能给出候选，让它下一轮选对。</p>
     */
    public List<JavaSymbol> search(String keyword) {
        String lower = keyword.toLowerCase(Locale.ROOT);
        List<JavaSymbol> result = new ArrayList<>();
        for (Map.Entry<String, List<JavaSymbol>> e : byName.entrySet()) {
            if (e.getKey().toLowerCase(Locale.ROOT).contains(lower)) {
                result.addAll(e.getValue());
            }
        }
        // 同名高亮优先：完全相等 > 前缀 > 子串
        result.sort(Comparator
                .comparingInt((JavaSymbol s) -> score(s.name(), lower))
                .thenComparing(JavaSymbol::filePath)
                .thenComparingInt(JavaSymbol::line));
        return List.copyOf(result);
    }

    /** 取所有类型级符号（构建 RepoMap 用）。 */
    public List<JavaSymbol> allTypes() {
        List<JavaSymbol> types = new ArrayList<>();
        byFile.values().forEach(symbols -> symbols.stream()
                .filter(s -> s.kind().isType())
                .forEach(types::add));
        types.sort(Comparator.comparing(JavaSymbol::filePath));
        return List.copyOf(types);
    }

    public List<String> files() {
        return List.copyOf(byFile.keySet());
    }

    public int symbolCount() {
        return byName.values().stream().mapToInt(List::size).sum();
    }

    private static int score(String name, String lowerKeyword) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals(lowerKeyword)) {
            return 0;
        }
        return lower.startsWith(lowerKeyword) ? 1 : 2;
    }
}
