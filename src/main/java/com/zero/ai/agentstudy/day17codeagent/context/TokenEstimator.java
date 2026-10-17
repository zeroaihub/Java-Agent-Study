package com.zero.ai.agentstudy.day17codeagent.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Token 估算器。
 *
 * <p>真实的 tokenizer（cl100k / o200k）需要额外的词表文件，这里提供一个
 * <b>够用于预算控制</b>的近似：英文按 ~4 字符/token，中英混杂时中文按 ~1.5 字符/token。</p>
 *
 * <p>为什么不直接调 API 数 token：数一次要一次网络往返，
 * 在「要不要再多塞一个文件」这种每秒可能判断几十次的场景里是不可接受的延迟。</p>
 *
 * <p><b>要害是「宁可高估」</b>：把预算估少了，最坏是少给模型一点上下文；
 * 估多了会直接触发 {@code context length exceeded}，整个任务报废。所以这里向上取整。</p>
 *
 * @author ZeroAi
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    /** 估算文本的 token 数。 */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double tokens = 0.0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                tokens += 0.8;     // 中文按 ~1.25 字符/token 折算
            } else if (Character.isWhitespace(c)) {
                tokens += 0.25;    // 空白通常被合并，但仍占一定 token
            } else {
                tokens += 0.28;    // 英文/符号 ≈ 3.5 字符/token
            }
        }
        return (int) Math.ceil(tokens);
    }

    /** 估算多行文本（换行本身也占一个 token，不要漏）。 */
    public static int estimateLines(List<String> lines) {
        return estimate(String.join("\n", lines)) + Math.max(0, lines.size() - 1);
    }

    /** 是否 CJK 字符。 */
    public static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES;
    }

    /**
     * 轻量分词：ASCII 按词切分并小写化；中文同时产出<b>一字词与二字词</b>。
     *
     * <p>为什么中文要加 bigram：单字「订」在中文里几乎没有区分度，
     * 「订单」才有。这与 Day15 的 CJK 处理方法保持一致。</p>
     */
    public static List<String> tokenize(String text) {
        List<String> terms = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return terms;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder ascii = new StringBuilder();
        StringBuilder cjkRun = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (isCjk(c)) {
                flushAscii(terms, ascii);
                cjkRun.append(c);
            } else {
                flushCjk(terms, cjkRun);
                if (Character.isLetterOrDigit(c)) {
                    ascii.append(c);
                } else {
                    flushAscii(terms, ascii);
                }
            }
        }
        flushAscii(terms, ascii);
        flushCjk(terms, cjkRun);
        return List.copyOf(terms);
    }

    private static void flushAscii(List<String> out, StringBuilder buf) {
        if (buf.length() > 0) {
            out.add(buf.toString());
            buf.setLength(0);
        }
    }

    private static void flushCjk(List<String> out, StringBuilder buf) {
        if (buf.length() == 0) {
            return;
        }
        String s = buf.toString();
        for (int i = 0; i < s.length(); i++) {
            out.add(s.substring(i, i + 1));
            if (i + 2 <= s.length()) {
                out.add(s.substring(i, i + 2));
            }
        }
        buf.setLength(0);
    }
}
