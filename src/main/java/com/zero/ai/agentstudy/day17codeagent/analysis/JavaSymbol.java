package com.zero.ai.agentstudy.day17codeagent.analysis;

import java.util.List;
import java.util.Map;

/**
 * 一个代码符号（类 / 接口 / 方法 / 字段）。
 *
 * <p>它是「代码理解」的最小颗粒，后续三件事都建立在它之上：</p>
 * <ul>
 *     <li><b>RepoMap</b> 由它压缩而成，给 LLM 一份「这仓库有什么」的索引；</li>
 *     <li><b>上下文选择</b> 按符号相关度挑文件，而不是全文塞进去；</li>
 *     <li><b>选址</b> （改哪个文件的哪一段）靠 {@code line} 定位。</li>
 * </ul>
 *
 * @param name       符号名
 * @param kind       符号类型
 * @param ownerType  所属顶层类型名（嵌套成员才有意义，顶层类型则与自己同名）
 * @param signature  声明签名，如 {@code add(Money) : Money}
 * @param filePath   所在文件相对路径
 * @param line       声明所在行（1-based）
 * @param modifiers  修饰符，如 {@code public static}
 * @author ZeroAi
 */
public record JavaSymbol(
        String name,
        SymbolKind kind,
        String ownerType,
        String signature,
        String filePath,
        int line,
        List<String> modifiers
) {

    /** 是否可供外部调用（public）。 */
    public boolean isPublic() {
        return modifiers.contains("public");
    }

    public String modifiersText() {
        return String.join(" ", modifiers);
    }

    @Override
    public String toString() {
        return kind.display() + " " + name + " @ " + filePath + ":" + line;
    }

    /** 转成 Map，便于序列化给 LLM / 前端。 */
    public Map<String, Object> toMap() {
        return Map.of(
                "name", name,
                "kind", kind.name(),
                "signature", signature,
                "file", filePath,
                "line", line);
    }
}
