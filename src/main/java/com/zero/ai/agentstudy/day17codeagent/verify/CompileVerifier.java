package com.zero.ai.agentstudy.day17codeagent.verify;

import com.zero.ai.agentstudy.day17codeagent.core.common.CodeException;
import com.zero.ai.agentstudy.day17codeagent.core.common.CodeErrorCode;
import com.zero.ai.agentstudy.day17codeagent.repo.VirtualFileSystem;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 编译验证器：<b>真的调用 javac</b> 来验证补丁是否可用。
 *
 * <h3>为什么这是整个 Day17 的地基</h3>
 * <p>「模型说改好了」不等于「代码能跑」。Code Agent 与聊天机器人的分水岭就在于：
 * 它必须能<b>自己验证</b>。没有编译验证，就只能在 PR 里指望人类 Reviewer 去发现编译错误——
 * 那这个 Agent 的价值就只剩「帮你写代码片段」了。</p>
 *
 * <p>这里用 JDK 自带的 {@code javax.tools.JavaCompiler}（javac 的官方 API），
 * 零第三方依赖，在我们的机器上就是真的编译了一遍。</p>
 *
 * <h3>为什么优先用 API 而不是 {@code Runtime.exec("javac")}</h3>
 * <ul>
 *     <li>拿到的诊断是<b>结构化的</b>（file / line / column / code），不用从字符串里正则抠行号；</li>
 *     <li>没有进程启动开销（自修复循环里要跑好几轮，每轮几秒的 javac 启动是很大的成本）；</li>
 *     <li>不必处理 Windows 的 {@code javac.exe} 路径、空格、编码等琐碎问题。</li>
 * </ul>
 *
 * @author ZeroAi
 */
public class CompileVerifier {

    private final boolean failOnWarning;

    public CompileVerifier(boolean failOnWarning) {
        this.failOnWarning = failOnWarning;
    }

    public static CompileVerifier defaultVerifier() {
        return new CompileVerifier(false);
    }

    public static CompileVerifier strictNoWarning() {
        return new CompileVerifier(true);
    }

    /** 当前运行环境是否提供编译器（跑在 JRE 上就没有 —— 必须显式检查，不能默默失败）。 */
    public static boolean available() {
        return ToolProvider.getSystemJavaCompiler() != null;
    }

    /**
     * 编译整个 VFS 里的 Java 文件。
     *
     * @param vfs       仓库
     * @param outputDir class 文件输出目录
     */
    public CompilationResult compile(VirtualFileSystem vfs, Path outputDir) {
        List<Path> sources = vfs.listFilesEndingWith("", ".java").stream()
                .map(vfs::resolve)
                .toList();
        return compileFiles(vfs, sources, outputDir);
    }

    /** 编译指定的一批文件。 */
    public CompilationResult compileFiles(VirtualFileSystem vfs, List<Path> files, Path outputDir) {
        requireCompiler();
        long start = System.currentTimeMillis();

        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new CodeException(CodeErrorCode.INTERNAL_ERROR, "无法创建编译输出目录", e);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(collector, Locale.ROOT, StandardCharsets.UTF_8)) {

            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(files);
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(outputDir));

            List<String> options = new ArrayList<>(List.of("-encoding", "UTF-8", "-g"));
            JavaCompiler.CompilationTask task =
                    compiler.getTask(null, fileManager, collector, options, null, units);

            boolean ok = task.call();
            long elapsed = System.currentTimeMillis() - start;

            List<Diagnostic> diagnostics = collector.getDiagnostics().stream()
                    .map(d -> new Diagnostic(
                            mapKind(d.getKind()),
                            relativize(vfs, d),
                            d.getLineNumber() < 0 ? -1 : d.getLineNumber(),
                            d.getColumnNumber() < 0 ? -1 : d.getColumnNumber(),
                            d.getCode(),
                            d.getMessage(Locale.ROOT) == null ? "" : d.getMessage(Locale.ROOT)))
                    .toList();

            List<Diagnostic> errors = diagnostics.stream().filter(Diagnostic::isError).toList();
            List<Diagnostic> warnings = diagnostics.stream()
                    .filter(d -> d.kind() == Diagnostic.Kind.WARNING).toList();

            boolean success = errors.isEmpty() && (!failOnWarning || warnings.isEmpty());
            return new CompilationResult(success, diagnostics, files.size(), elapsed,
                    outputDir, success ? "编译通过" : "编译失败：共 " + errors.size() + " 个错误");
        } catch (IOException e) {
            throw new CodeException(CodeErrorCode.INTERNAL_ERROR, "编译过程发生 I/O 错误", e);
        }
    }

    /**
     * 编译一段内存中的源码（用于快速语法自检，无需落盘）。
     *
     * <p>典型场景：自修复循环每轮只改了一个方法，没必要把整个仓库重新编译一遍。</p>
     */
    public CompilationResult compileSnippet(String className, String source) {
        requireCompiler();
        long start = System.currentTimeMillis();

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        InMemorySourceObject unit = new InMemorySourceObject(className, source);
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(collector, Locale.ROOT, StandardCharsets.UTF_8)) {

            // 关键：不能把 CLASS_OUTPUT 设成空列表（javac 会抛
            // IllegalArgumentException: empty path for directory）。
            // 正确做法是用一个自定义 FileManager 拦下 class 输出，写进内存，
            // 这样编译检查期间磁盘上不会留下任何临时文件。
            InMemoryFileManager inMemoryManager = new InMemoryFileManager(fileManager);

            JavaCompiler.CompilationTask task = compiler.getTask(
                    null, inMemoryManager, collector,
                    List.of("-encoding", "UTF-8", "-proc:none"),
                    null, List.of(unit));
            boolean ok = task.call();

            List<Diagnostic> diagnostics = collector.getDiagnostics().stream()
                    .map(d -> new Diagnostic(
                            mapKind(d.getKind()),
                            className + ".java",
                            d.getLineNumber() < 0 ? -1 : d.getLineNumber(),
                            d.getColumnNumber() < 0 ? -1 : d.getColumnNumber(),
                            d.getCode(),
                            d.getMessage(Locale.ROOT) == null ? "" : d.getMessage(Locale.ROOT)))
                    .toList();
            long errors = diagnostics.stream().filter(Diagnostic::isError).count();
            boolean success = errors == 0 && ok;
            return new CompilationResult(success, diagnostics, 1,
                    System.currentTimeMillis() - start, null,
                    success ? "片段编译通过" : "片段编译失败：" + errors + " 个错误");
        } catch (IOException e) {
            throw new CodeException(CodeErrorCode.INTERNAL_ERROR, "内存编译发生 I/O 错误", e);
        }
    }

    private void requireCompiler() {
        if (!available()) {
            throw new CodeException(CodeErrorCode.COMPILER_UNAVAILABLE,
                    "当前 JVM 没有提供 JavaCompiler（可能运行在 JRE 上）。"
                            + "Code Agent 必须在 JDK 上运行，否则无法自验证。");
        }
    }

    private static Diagnostic.Kind mapKind(javax.tools.Diagnostic.Kind kind) {
        return switch (kind) {
            case ERROR -> Diagnostic.Kind.ERROR;
            case WARNING, MANDATORY_WARNING -> Diagnostic.Kind.WARNING;
            case NOTE -> Diagnostic.Kind.NOTE;
            default -> Diagnostic.Kind.OTHER;
        };
    }

    private static String relativize(VirtualFileSystem vfs,
                                     javax.tools.Diagnostic<? extends JavaFileObject> d) {
        if (d.getSource() == null) {
            return "(unknown)";
        }
        URI uri = d.getSource().toUri();
        try {
            Path abs = Path.of(uri);
            return vfs.root().relativize(abs.toAbsolutePath().normalize()).toString()
                    .replace('\\', '/');
        } catch (RuntimeException e) {
            return String.valueOf(uri);
        }
    }

    /**
     * 把编译产出的 class 拦在内存里的文件管理器。
     *
     * <p>语法检查阶段根本不需要 class 文件，让它落盘只会产生
     * 一堆需要清理的临时文件——在自修复循环里每轮都产生一批，很快就会堆积。</p>
     */
    private static class InMemoryFileManager
            extends javax.tools.ForwardingJavaFileManager<StandardJavaFileManager> {

        InMemoryFileManager(StandardJavaFileManager delegate) {
            super(delegate);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                   JavaFileObject.Kind kind,
                                                   javax.tools.FileObject sibling) {
            return new SimpleJavaFileObject(
                    URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind) {
                @Override
                public java.io.OutputStream openOutputStream() {
                    return new java.io.ByteArrayOutputStream();
                }
            };
        }
    }

    /** 内存形式的源码对象。 */
    private static class InMemorySourceObject extends SimpleJavaFileObject {
        private final String source;

        InMemorySourceObject(String className, String source) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension),
                    Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    /**
     * 编译结果。
     *
     * @param success     是否编译通过
     * @param diagnostics 全部诊断（含警告）
     * @param fileCount   参与编译的文件数
     * @param elapsedMs   耗时
     * @param outputDir   class 输出目录（内存编译时为 null）
     * @param summary     一句话摘要
     */
    public record CompilationResult(
            boolean success,
            List<Diagnostic> diagnostics,
            int fileCount,
            long elapsedMs,
            Path outputDir,
            String summary
    ) {
        public List<Diagnostic> errors() {
            return diagnostics.stream().filter(Diagnostic::isError).toList();
        }

        public List<Diagnostic> warnings() {
            return diagnostics.stream()
                    .filter(d -> d.kind() == Diagnostic.Kind.WARNING).toList();
        }

        /**
         * 生成喂给 LLM 的错误反馈（限量，避免把整个输出塞回去）。
         */
        public String feedbackForModel(int maxItems) {
            if (success) {
                return "编译通过，无需修改。";
            }
            StringBuilder sb = new StringBuilder("编译未通过，请修正以下错误后重新输出补丁：\n");
            errors().stream().limit(maxItems)
                    .forEach(d -> sb.append("- ").append(d.forPrompt()).append('\n'));
            int rest = errors().size() - Math.min(maxItems, errors().size());
            if (rest > 0) {
                sb.append("- （还有 ").append(rest).append(" 个错误未列出，请一并检查）\n");
            }
            return sb.toString();
        }

        public String feedbackForModel() {
            return feedbackForModel(5);
        }

        /** 错误签名集合，用于「两轮错误是否相同」的无进展判定。 */
        public List<String> errorSignatures() {
            return errors().stream().map(Diagnostic::signature).toList();
        }

        @Override
        public String toString() {
            return "CompilationResult{" + summary + ", files=" + fileCount
                    + ", errors=" + errors().size()
                    + ", warnings=" + warnings().size()
                    + ", " + elapsedMs + "ms}";
        }
    }
}
