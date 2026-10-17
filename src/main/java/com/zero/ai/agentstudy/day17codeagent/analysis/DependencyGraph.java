package com.zero.ai.agentstudy.day17codeagent.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 文件级依赖图：谁引用了谁。
 *
 * <h3>为什么需要它</h3>
 * <p>改一个接口，受影响的不是只有这一个文件。没有依赖图，Code Agent 改完 {@code OrderRepository}
 * 却不知道 {@code OrderService} 会连坐，编译阶段才炸，白白烧掉一轮自修复预算。</p>
 *
 * <p>有了依赖图可以做三件事：</p>
 * <ol>
 *     <li><b>影响面估算</b>：改 X 之前先算出「必须一起看」的文件集合，一次性塞进上下文；</li>
 *     <li><b>反向依赖</b>：回答「谁用到了我」，这是重构能否安全进行的依据；</li>
 *     <li><b>环检测</b>：包级循环依赖是架构腐化的信号。</li>
 * </ol>
 *
 * @author ZeroAi
 */
public class DependencyGraph {

    /** 依赖边：file → 依赖的 file 集合。 */
    private final Map<String, Set<String>> edges = new TreeMap<>();

    /** 全名（含包名）→ 定义它的文件。 */
    private final Map<String, String> fullyQualifiedIndex = new LinkedHashMap<>();

    /** 简单类名 → 定义它的文件。 */
    private final Map<String, String> simpleNameIndex = new LinkedHashMap<>();

    private final Map<String, List<String>> importsByFile = new LinkedHashMap<>();

    /** 空图。 */
    public static DependencyGraph empty() {
        return new DependencyGraph();
    }

    /** 由「路径 → 源码」构建依赖图。 */
    public static DependencyGraph build(Map<String, String> sources) {
        DependencyGraph graph = new DependencyGraph();
        // 第一遍：建立「类名 → 文件」索引
        sources.forEach((path, source) -> {
            if (!path.endsWith(".java")) {
                return;
            }
            LightweightJavaParser.SourceInfo info = LightweightJavaParser.parse(path, source);
            String pkg = info.packageName();
            info.symbols().stream()
                    .filter(s -> s.kind().isType())
                    .forEach(t -> {
                        String simple = t.name();
                        String fq = pkg.isBlank() ? simple : pkg + "." + simple;
                        graph.fullyQualifiedIndex.put(fq, path);
                        graph.simpleNameIndex.putIfAbsent(simple, path);
                    });
            graph.importsByFile.put(path, info.imports());
        });
        // 第二遍：把 import 解析成文件级边
        graph.importsByFile.forEach((path, imports) -> graph.edges.putIfAbsent(path, new TreeSet<>()));
        graph.importsByFile.forEach((path, imports) -> {
            for (String imp : imports) {
                String target = graph.fullyQualifiedIndex.get(imp);
                if (target == null && imp.endsWith(".*")) {
                    // 通配 import：无法精确定位，退化为「该包下所有已知文件」
                    String pkg = imp.substring(0, imp.length() - 2);
                    for (Map.Entry<String, String> e : graph.fullyQualifiedIndex.entrySet()) {
                        String fq = e.getKey();
                        if (fq.startsWith(pkg + ".")
                                && fq.substring(pkg.length() + 1).indexOf('.') < 0) {
                            graph.addEdge(path, e.getValue());
                        }
                    }
                    continue;
                }
                if (target != null) {
                    graph.addEdge(path, target);
                } else {
                    // 同包类：按简单名兜底解析
                    String simple = imp.substring(imp.lastIndexOf('.') + 1);
                    String samePackage = graph.simpleNameIndex.get(simple);
                    if (samePackage != null && !samePackage.equals(path)) {
                        graph.addEdge(path, samePackage);
                    }
                }
            }
        });
        // 第三遍：同包引用——这才是真实仓库里依赖的「大头」
        // 只靠 import 抓不到同包类：它们压根没有 import 语句。
        graph.simpleNameIndex.forEach((simpleName, definedIn) -> {
            Pattern usage = Pattern.compile("\\b" + Pattern.quote(simpleName) + "\\b");
            sources.forEach((path, source) -> {
                if (!path.endsWith(".java") || path.equals(definedIn)) {
                    return;
                }
                String clean = LightweightJavaParser.stripCommentsAndStrings(source);
                if (usage.matcher(clean).find()) {
                    graph.addEdge(path, definedIn);
                }
            });
        });
        return graph;
    }

    public void addEdge(String from, String to) {
        if (from.equals(to)) {
            return;
        }
        edges.computeIfAbsent(from, k -> new TreeSet<>()).add(to);
        edges.computeIfAbsent(to, k -> new TreeSet<>());
    }

    /** 某个文件依赖了哪些文件（出边）。 */
    public Set<String> dependenciesOf(String path) {
        return Set.copyOf(edges.getOrDefault(path, Set.of()));
    }

    /**
     * 谁依赖了它（反向边，入边）。
     *
     * <p>这是「我能不能安全删掉这个方法」的第一手依据。</p>
     */
    public Set<String> dependentsOf(String path) {
        Set<String> result = new TreeSet<>();
        edges.forEach((from, tos) -> {
            if (tos.contains(path)) {
                result.add(from);
            }
        });
        return Set.copyOf(result);
    }

    /**
     * 影响面：改动 {@code paths} 时，必须一并检查的文件（含自身）。
     *
     * <p>这里取「反向依赖」而非正向依赖——因为改了 X，需要跟着改的是<b>用 X 的人</b>，
     * 而不是 X 依赖的人。</p>
     */
    public Set<String> impactSet(List<String> paths) {
        Set<String> result = new LinkedHashSet<>(paths);
        List<String> queue = new ArrayList<>(paths);
        while (!queue.isEmpty()) {
            String current = queue.remove(0);
            for (String dependent : dependentsOf(current)) {
                if (result.add(dependent)) {
                    queue.add(dependent);
                }
            }
        }
        return Set.copyOf(result);
    }

    /** 是否存在包级别的环（简化：文件级环检测）。 */
    public boolean hasCycle() {
        Set<String> visiting = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        for (String node : edges.keySet()) {
            if (detectCycle(node, visiting, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean detectCycle(String node, Set<String> visiting, Set<String> visited) {
        if (visiting.contains(node)) {
            return true;
        }
        if (visited.contains(node)) {
            return false;
        }
        visiting.add(node);
        for (String next : edges.getOrDefault(node, Set.of())) {
            if (detectCycle(next, visiting, visited)) {
                return true;
            }
        }
        visiting.remove(node);
        visited.add(node);
        return false;
    }

    public Map<String, Set<String>> edges() {
        return Map.copyOf(edges);
    }

    public int nodeCount() {
        return edges.size();
    }

    public int edgeCount() {
        return edges.values().stream().mapToInt(Set::size).sum();
    }
}
