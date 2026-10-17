package com.zero.ai.agentstudy.day17codeagent.repo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 仓库快照（不可变）：某一时刻全部文件的「路径 → 内容」视图 + 内容指纹。
 *
 * <h3>为什么需要指纹</h3>
 * <p>Code Agent 最隐蔽的一类失败是「看起来在忙，其实一动没动」：LLM 连续三轮给出的补丁
 * 打完之后文件内容与上一轮完全一致。如果只看「有没有报错」，循环会一直空转到重试上限然后
 * 对外宣称「修复失败」，浪费了一整轮配额。
 * 有了指纹，就可以在 {@code SelfRepairLoop} 里做<b>无进展检测</b>：</p>
 * <pre>
 *   if (snapshotAfter.fingerprint().equals(snapshotBefore.fingerprint()))
 *       → 判定 REPAIR_NO_PROGRESS，提前退出
 * </pre>
 *
 * <p>另外，gitops 模块用它算增量变更集，比逐文件比对字符串便宜得多。</p>
 *
 * @param files       路径 → 内容，key 已排序（TreeMap），保证遍历顺序稳定
 * @param fingerprint 基于「路径 + 内容」的 SHA-256 摘要，内容一致则指纹一致
 * @author ZeroAi
 */
public record RepoSnapshot(
        Map<String, String> files,
        String fingerprint
) {

    public RepoSnapshot {
        files = Collections.unmodifiableMap(new TreeMap<>(files));
    }

    /** 空快照。 */
    public static RepoSnapshot empty() {
        return new RepoSnapshot(Map.of(), fingerprintOf(Map.of()));
    }

    /** 由一组文件构建快照并计算指纹。 */
    public static RepoSnapshot of(Map<String, String> files) {
        Map<String, String> copy = files == null ? Map.of() : files;
        return new RepoSnapshot(copy, fingerprintOf(new TreeMap<>(copy)));
    }

    /** 取单个文件内容，不存在返回 null。 */
    public String get(String path) {
        return files.get(path);
    }

    public boolean contains(String path) {
        return files.containsKey(path);
    }

    public List<String> paths() {
        return List.copyOf(files.keySet());
    }

    public int size() {
        return files.size();
    }

    public boolean isEmpty() {
        return files.isEmpty();
    }

    /** 与另一份快照内容是否完全一致（等价于指纹相同）。 */
    public boolean sameAs(RepoSnapshot other) {
        return other != null && fingerprint.equals(other.fingerprint());
    }

    /**
     * 与另一份快照的差异：新增 / 修改 / 删除三类路径。
     *
     * <p>判定依据是内容而非修改时间——修改时间会因为「touch 了一下内容没变」而误报。</p>
     */
    public Diff diff(RepoSnapshot other) {
        TreeMap<String, String> added = new TreeMap<>();
        TreeMap<String, String> modified = new TreeMap<>();
        TreeMap<String, String> removed = new TreeMap<>();

        for (Map.Entry<String, String> e : other.files().entrySet()) {
            String before = files.get(e.getKey());
            if (before == null) {
                added.put(e.getKey(), e.getValue());
            } else if (!before.equals(e.getValue())) {
                modified.put(e.getKey(), e.getValue());
            }
        }
        for (String path : files.keySet()) {
            if (!other.files.containsKey(path)) {
                removed.put(path, files.get(path));
            }
        }
        return new Diff(Map.copyOf(added), Map.copyOf(modified), Map.copyOf(removed));
    }

    /** 变更是否为空。 */
    public boolean isSameAs(RepoSnapshot other) {
        return sameAs(other);
    }

    /** 转成便于打印的行数统计。 */
    public Map<String, Integer> lineCounts() {
        Map<String, Integer> result = new LinkedHashMap<>();
        files.forEach((path, content) -> {
            if (content == null || content.isEmpty()) {
                result.put(path, 0);
                return;
            }
            int lines = 1;
            for (int i = 0; i < content.length(); i++) {
                if (content.charAt(i) == '\n') {
                    lines++;
                }
            }
            result.put(path, lines);
        });
        return result;
    }

    /**
     * 计算指纹：逐个追加 {@code path\0content\0}，保证「换个表名」也算变化。
     *
     * <p>注意不要把 map 直接 toString —— 那样只要顺序变了指纹就变，
     * 而 HashMap 的迭代顺序在不同 JDK 间都可能不同。</p>
     */
    public static String fingerprintOf(Map<String, String> sorted) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // 分隔符必须是 byte[]：MessageDigest 没有 update(int) 重载
            byte[] separator = {0};
            for (Map.Entry<String, String> e : sorted.entrySet()) {
                digest.update(e.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update(separator);
                digest.update(e.getValue() == null ? new byte[0]
                        : e.getValue().getBytes(StandardCharsets.UTF_8));
                digest.update(separator);
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 两个快照之间的差异结果。
     *
     * @param added    在 other 中新增的文件
     * @param modified 内容发生变化的文件
     * @param removed  在 other 中被删除的文件
     */
    public record Diff(
            Map<String, String> added,
            Map<String, String> modified,
            Map<String, String> removed
    ) {
        public boolean isEmpty() {
            return added.isEmpty() && modified.isEmpty() && removed.isEmpty();
        }

        /** 变更的文件总数，用于测试通过率与 PR 摘要。 */
        public int changedCount() {
            return added.size() + modified.size() + removed.size();
        }

        @Override
        public String toString() {
            return "Diff{+" + added.size() + " ~" + modified.size() + " -" + removed.size() + "}";
        }
    }
}
