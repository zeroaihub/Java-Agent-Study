package com.zero.ai.agentstudy.day17codeagent.repo;

import com.zero.ai.agentstudy.day17codeagent.core.common.CodeException;
import com.zero.ai.agentstudy.day17codeagent.core.common.CodeErrorCode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 虚拟文件系统（VFS）——Code Agent 唯一的读写入口。
 *
 * <h3>为什么必须有它</h3>
 * <p>LLM 生成的路径是不可信输入。它可能因为幻觉写出
 * {@code ../../../../etc/passwd}，也可能被提示词注入引导去改仓库外的文件。
 * 如果每个工具各自 {@code Files.write(path)}，安全校验就会散落各处并被逐渐遗忘。
 * VFS 把「这条路径到底能不能落盘」收敛为<b>唯一的一道门</b>。</p>
 *
 * <h3>三条铁律</h3>
 * <ol>
 *     <li><b>相对路径进，绝对路径出</b>：外部只传仓库内相对路径，绝对路径由本类拼装；</li>
 *     <li><b>任何路径都要规范化和根校验</b>：{@code normalize()} 消解 {@code ..}，再把候选路径与根做
 *         <b>按路径元素</b>比较；</li>
 *     <li><b>写入设体积上限</b>：LLM 一次吐几十兆内容的场景真实存在，必须在写之前就挡住。</li>
 * </ol>
 *
 * <p><b>坑（真实踩过的）</b>：用 {@code candidate.toString().startsWith(root.toString())} 做校验是错的，
 * 根 {@code /tmp/work} 会放行 {@code /tmp/workplace-evil/x}。必须用 {@link Path#startsWith(Path)}，
 * 它是按「路径元素」逐个比较的，workplace ≠ work。下面 {@link #resolve} 用的就是这个。</p>
 *
 * @author ZeroAi
 */
public class VirtualFileSystem {

    /** 默认单文件体积上限：1 MB。 */
    public static final int DEFAULT_MAX_FILE_BYTES = 1024 * 1024;

    private final Path root;
    private final int maxFileBytes;

    public VirtualFileSystem(Path root, int maxFileBytes) {
        CodeException.requireNonNull(root, "root");
        if (maxFileBytes <= 0) {
            throw new CodeException(CodeErrorCode.INVALID_ARGUMENT, "maxFileBytes 必须为正数");
        }
        this.root = root.toAbsolutePath().normalize();
        this.maxFileBytes = maxFileBytes;
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new CodeException(CodeErrorCode.INTERNAL_ERROR, "无法创建仓库根目录：" + this.root, e);
        }
    }

    /** 在系统临时目录下创建一个一次性仓库（Demo / 单测友好）。 */
    public static VirtualFileSystem temp(String name) {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "day17-vfs", name);
        return new VirtualFileSystem(dir, DEFAULT_MAX_FILE_BYTES);
    }

    public Path root() {
        return root;
    }

    public int maxFileBytes() {
        return maxFileBytes;
    }

    // =====================================================================
    // 路径安全：本类的心脏
    // =====================================================================

    /**
     * 把外部传入的相对路径安全地解析为绝对路径。
     *
     * <p>统一处理：Windows 反斜杠 → 统一 {@code /}、剥离前导 {@code /}（避免 {@code Path.resolve} 把
     * 绝对路径当作新根）、{@code normalize()} 消解 {@code .} 与 {@code ..}，最后做根校验。</p>
     *
     * @throws CodeException CodeErrorCode.PATH_TRAVERSAL 越界时抛出
     */
    public Path resolve(String relativePath) {
        CodeException.requireText(relativePath, "relativePath");

        // 1. 统一分隔符
        String unified = relativePath.replace('\\', '/');

        // 2. 绝对路径输入本身就是越权信号：Agent 只应在仓库内工作
        if (unified.startsWith("/") || unified.matches("^[A-Za-z]:.*")) {
            throw new CodeException(
                    CodeErrorCode.PATH_TRAVERSAL,
                    "不接受绝对路径：" + relativePath + "，请提供仓库内相对路径");
        }

        // 3. 消解 . 与 ..
        Path candidate = root.resolve(unified).normalize().toAbsolutePath();

        // 4. 按「路径元素」比较根 —— 不能用字符串 startsWith
        if (!candidate.startsWith(root)) {
            throw new CodeException(
                    CodeErrorCode.PATH_TRAVERSAL,
                    "路径越界：" + relativePath + " → " + candidate + " 不在仓库根 " + root + " 之内");
        }
        return candidate;
    }

    /** 相对路径是否合法（不抛异常，供前置判断使用）。 */
    public boolean isInside(String relativePath) {
        try {
            resolve(relativePath);
            return true;
        } catch (CodeException e) {
            return false;
        }
    }

    // =====================================================================
    // 读写操作
    // =====================================================================

    /** 写入文件（自动创建父目录）。返回写入后的文件快照。 */
    public RepoFile write(String relativePath, String content) {
        String safeContent = content == null ? "" : content;
        byte[] bytes = safeContent.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxFileBytes) {
            throw new CodeException(
                    CodeErrorCode.FILE_TOO_LARGE,
                    "文件过大：" + relativePath + " 共 " + bytes.length + " 字节，上限 " + maxFileBytes + " 字节");
        }
        Path target = resolve(relativePath);
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(target, bytes);
            return read(relativePath);
        } catch (IOException e) {
            throw new CodeException(CodeErrorCode.INTERNAL_ERROR, "写入文件失败：" + relativePath, e);
        }
    }

    /** 读取文件，不存在则抛 FILE_NOT_FOUND。 */
    public RepoFile read(String relativePath) {
        Path target = resolve(relativePath);
        if (!Files.isRegularFile(target)) {
            throw new CodeException(CodeErrorCode.FILE_NOT_FOUND, "文件不存在：" + relativePath);
        }
        try {
            String content = Files.readString(target, StandardCharsets.UTF_8);
            FileTime time = Files.getLastModifiedTime(target);
            return new RepoFile(
                    relativePath, content, content.getBytes(StandardCharsets.UTF_8).length,
                    !Files.isWritable(target), time.toInstant());
        } catch (IOException e) {
            throw new CodeException(CodeErrorCode.INTERNAL_ERROR, "读取文件失败：" + relativePath, e);
        }
    }

    /**
     * 读取文件，不存在时返回 null 而不是抛异常。
     *
     * <p>为何要提供这个变体：工具的失败应当是返回值。Agent 主循环里「文件不存在」是常态分支
     * （比如先探测某文件是否存在），用异常表达会污染控制流。</p>
     */
    public RepoFile find(String relativePath) {
        Path target;
        try {
            target = resolve(relativePath);
        } catch (CodeException e) {
            return null;
        }
        if (!Files.isRegularFile(target)) {
            return null;
        }
        return read(relativePath);
    }

    public boolean exists(String relativePath) {
        return find(relativePath) != null;
    }

    /** 删除文件（不存在视为幂等成功，返回 false）。 */
    public boolean delete(String relativePath) {
        Path target = resolve(relativePath);
        if (!Files.exists(target)) {
            return false;
        }
        try {
            return Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new CodeException(CodeErrorCode.INTERNAL_ERROR, "删除文件失败：" + relativePath, e);
        }
    }

    /**
     * 列出目录下所有文件（相对路径，排序稳定）。
     *
     * <p>排序是刻意的：LLM 对输入顺序敏感，随机顺序会让同样的任务产出不同的补丁，
     * 评测无法复现。</p>
     */
    public List<String> listFiles(String relativeDir) {
        Path dir = relativeDir == null || relativeDir.isBlank() ? root : resolve(relativeDir);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    result.add(relativize(file));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        result.sort(Comparator.naturalOrder());
        return List.copyOf(result);
    }

    /** 绝对路径 → 仓库内相对路径。 */
    public String relativize(Path absolute) {
        return root.relativize(absolute.normalize().toAbsolutePath()).toString().replace('\\', '/');
    }

    /** 只保留指定后缀的文件。 */
    public List<String> listFilesEndingWith(String relativeDir, String suffix) {
        Set<String> kept = new TreeSet<>();
        for (String p : listFiles(relativeDir)) {
            if (p.endsWith(suffix)) {
                kept.add(p);
            }
        }
        return List.copyOf(kept);
    }

    /** 导出整份快照（路径 → 内容），顺序稳定。 */
    public RepoSnapshot snapshot() {
        Map<String, String> content = new java.util.TreeMap<>();
        for (String path : listFiles("")) {
            content.put(path, read(path).content());
        }
        return RepoSnapshot.of(content);
    }

    /**
     * 批量落盘一组文件，常用于「把 Mini 仓库铺出来」。
     */
    public void writeAll(java.util.Map<String, String> files) {
        files.forEach(this::write);
    }

    public static Instant now() {
        return Instant.now();
    }
}
