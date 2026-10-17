package com.zero.ai.agentstudy.day17codeagent.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量 Java 解析器：不用任何第三方库，提取 package / import / 类型 / 方法 / 字段。
 *
 * <h3>为什么不用 JavaParser / javac</h3>
 * <p>教程要零依赖、可离线跑。更重要的是——<b>解析「不完整、正在被改的代码」是常态</b>。
 * Code Agent 读到一半的文件常常处于语法错误状态（刚打完补丁还没修完），
 * 严格的 AST 解析器这时会直接抛异常，反而拿不到任何信息；
 * 而基于行 + 括号深度的宽松解析器依然能给出 90% 正确的符号位置。</p>
 *
 * <h3>最大的坑：注释与字符串里的「假代码」</h3>
 * <p>下面这段代码会让naive正则误报一个 {@code Service} 类：</p>
 * <pre>
 *   String tip = "public class FakeService {";   // ← 字符串里的类声明
 *   // public class CommentService {            ← 注释里的类声明
 * </pre>
 * <p>因此本类的第一步永远是 {@link #stripCommentsAndStrings}：
 * 把注释与字符串<b>替换为等长空格</b>（保留列偏移，行号才不会错位），再在干净文本上做正则匹配。</p>
 *
 * <p><b>诚实交代局限</b>：它不处理泛型嵌套过深、注解换行、多行 Lambda 边界等复杂语法。
 * 生产中应当换成 JavaParser（javaparser-core）或 javac 的 {@code Trees} API。
 * 这里的价值在于「零依赖即可跑通教学链路」，不要直接用于生产。</p>
 *
 * @author ZeroAi
 */
public final class LightweightJavaParser {

    private LightweightJavaParser() {
    }

    // ---------- 正则 ----------
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+(static\\s+)?([\\w.*]+)\\s*;");
    private static final Pattern TYPE = Pattern.compile(
            "(?:^|\\s)(?:public\\s+|final\\s+|abstract\\s+|sealed\\s+)*(class|interface|enum|record)\\s+(\\w+)");
    private static final Pattern METHOD = Pattern.compile(
            "^\\s*(?:(public|protected|private)\\s+)?(?:(static)\\s+)?(?:(final)\\s+)?"
                    + "(?:<[^>]*>\\s*)?([\\w.<>\\[\\]]+)\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\s*\\{");
    private static final Pattern FIELD = Pattern.compile(
            "^\\s*(?:(public|protected|private)\\s+)?(?:(static)\\s+)?(?:(final)\\s+)?"
                    + "([\\w.<>\\[\\]]+)\\s+(\\w+)\\s*(?:=[^;]*)?;");

    /**
     * 解析一份 Java 源码。
     *
     * @param path   文件相对路径（用于回填符号的位置信息）
     * @param source 源码内容
     */
    public static SourceInfo parse(String path, String source) {
        if (source == null || source.isBlank()) {
            return new SourceInfo("", List.of(), List.of());
        }
        String clean = stripCommentsAndStrings(source);
        String[] lines = clean.split("\n", -1);

        String packageName = "";
        List<String> imports = new ArrayList<>();
        List<JavaSymbol> symbols = new ArrayList<>();

        int depth = 0;                       // 花括号深度
        String currentTopType = null;        // 当前顶层类型名
        int typeDeclDepth = 0;               // 顶层类型声明时所在的深度
        List<String> currentTypeStack = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            // package / import 只在花括号外出现
            if (depth == 0) {
                Matcher pkg = PACKAGE.matcher(line);
                if (pkg.find()) {
                    packageName = pkg.group(1);
                }
                Matcher imp = IMPORT.matcher(line);
                if (imp.find()) {
                    imports.add(imp.group(2));
                    continue;
                }
            }

            int beforeDepth = depth;

            // ---- 类型声明 ----
            boolean typeMatched = false;
            Matcher type = TYPE.matcher(line);
            if (!trimmed.startsWith("//") && type.find()) {
                typeMatched = true;
                SymbolKind kind = switch (type.group(1)) {
                    case "class" -> SymbolKind.CLASS;
                    case "interface" -> SymbolKind.INTERFACE;
                    case "enum" -> SymbolKind.ENUM;
                    default -> SymbolKind.RECORD;
                };
                String typeName = type.group(2);
                if (beforeDepth == 0) {
                    currentTopType = typeName;
                    typeDeclDepth = 0;
                }
                currentTypeStack.add(typeName);
                symbols.add(new JavaSymbol(
                        typeName, kind, currentTopType,
                        type.group(1) + " " + typeName,
                        path, i + 1, modifiersOf(line)));
            }

            // ---- 方法 / 构造器 ----
            // 必须与类型声明互斥：record 的紧凑写法 `record Order(String id) {`
            // 会被 METHOD 正则同时命中。若不互斥，符号表里会多出一个幽灵「方法 Order」。
            Matcher method = METHOD.matcher(line);
            if (!typeMatched && method.find() && beforeDepth == 1 && currentTopType != null) {
                String returnType = method.group(4);
                String name = method.group(5);
                String params = method.group(6).trim();
                SymbolKind kind = name.equals(currentTopType)
                        ? SymbolKind.CONSTRUCTOR : SymbolKind.METHOD;
                String signature = (kind == SymbolKind.CONSTRUCTOR ? "" : returnType + " ")
                        + name + "(" + simplifyParams(params) + ")";
                symbols.add(new JavaSymbol(
                        name, kind, currentTopType, signature, path, i + 1, modifiersOf(line)));
            }

            // ---- 字段（仅取 static 常量与 public 字段，减少噪声） ----
            if (beforeDepth == 1 && currentTopType != null && trimmed.endsWith(";")) {
                Matcher field = FIELD.matcher(line);
                boolean looksLikeMethod = line.contains("(");
                if (field.find() && !looksLikeMethod) {
                    boolean isConst = field.group(2) != null && field.group(3) != null;
                    boolean isPublic = field.group(1) != null;
                    if (isConst || isPublic) {
                        symbols.add(new JavaSymbol(
                                field.group(5), SymbolKind.FIELD, currentTopType,
                                field.group(4) + " " + field.group(5),
                                path, i + 1, modifiersOf(line)));
                    }
                }
            }

            // ---- 更新深度 ----
            depth += countChar(line, '{') - countChar(line, '}');
            if (depth < beforeDepth && !currentTypeStack.isEmpty()) {
                // 离开某个类型作用域时出栈（简化处理：每次下降出栈一层）
                currentTypeStack.remove(currentTypeStack.size() - 1);
                if (depth == 0) {
                    currentTopType = null;
                }
            }
            if (depth < 0) {
                depth = 0;
            }
        }
        return new SourceInfo(packageName, List.copyOf(imports), List.copyOf(symbols));
    }

    /**
     * 把注释与字符串/字符字面量替换为<b>等长空格</b>。
     *
     * <p>等长是关键：如果直接删掉，行列偏移会整体左移，报告出来的行号虽不变但列对齐乱掉，
     * 更严重的是多行字符串被删会让后续行号错位，导致「改错地方」。</p>
     */
    public static String stripCommentsAndStrings(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int len = source.length();
        while (i < len) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < len) {
                char next = source.charAt(i + 1);
                if (next == '/') {
                    while (i < len && source.charAt(i) != '\n') {
                        out.append(' ');
                        i++;
                    }
                    continue;
                }
                if (next == '*') {
                    out.append("  ");
                    i += 2;
                    while (i < len) {
                        if (source.charAt(i) == '*' && i + 1 < len && source.charAt(i + 1) == '/') {
                            out.append("  ");
                            i += 2;
                            break;
                        }
                        out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                        i++;
                    }
                    continue;
                }
            }
            if (c == '"') {
                out.append(' ');
                i++;
                while (i < len) {
                    if (source.charAt(i) == '\\') {
                        out.append("  ");
                        i += 2;
                        continue;
                    }
                    if (source.charAt(i) == '"') {
                        out.append(' ');
                        i++;
                        break;
                    }
                    out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                continue;
            }
            if (c == '\'') {
                out.append(' ');
                i++;
                while (i < len) {
                    if (source.charAt(i) == '\\') {
                        out.append("  ");
                        i += 2;
                        continue;
                    }
                    if (source.charAt(i) == '\'') {
                        out.append(' ');
                        i++;
                        break;
                    }
                    out.append(' ');
                    i++;
                }
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String simplifyParams(String params) {
        if (params.isBlank()) {
            return "";
        }
        String[] parts = params.split(",");
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < parts.length; k++) {
            String p = parts[k].trim();
            // 去掉 final / 参数名，只留类型
            p = p.replaceAll("\\bfinal\\s+", "");
            int space = p.lastIndexOf(' ');
            String type = space > 0 ? p.substring(0, space).trim() : p;
            sb.append(type);
            if (k < parts.length - 1) {
                sb.append(", ");
            }
        }
        return sb.toString();
    }

    private static List<String> modifiersOf(String line) {
        List<String> mods = new ArrayList<>();
        if (line.contains("public ")) {
            mods.add("public");
        }
        if (line.contains("private ")) {
            mods.add("private");
        }
        if (line.contains("protected ")) {
            mods.add("protected");
        }
        if (line.contains("static ")) {
            mods.add("static");
        }
        if (line.contains("final ")) {
            mods.add("final");
        }
        return List.copyOf(mods);
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    /**
     * 单个文件的解析结果。
     *
     * @param packageName 包名
     * @param imports     import 列表
     * @param symbols     符号列表（按出现顺序）
     */
    public record SourceInfo(String packageName, List<String> imports, List<JavaSymbol> symbols) {
    }
}
