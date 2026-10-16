package com.zero.ai.agentstudy.day15knowledgeplatform.core.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 轻量文本分词器（面向检索场景，零第三方依赖）。
 *
 * <p>为什么不用 Lucene 的 SmartChineseAnalyzer 或 IKAnalyzer？
 * 因为本模块的定位是「零依赖可跑通」，而分词器是被 BM25 索引与本地 Embedding 共用的
 * 底层设施，引入重型 NLP 库会破坏这一约束。更重要的是——<b>分词策略必须与
 * 生产环境保持一致</b>，这里给出的策略（英文按词、中文按字 bigram）正是
 * 无词典场景下工业界的标准做法（Elasticsearch 的 CJK Bigram 即如此）。</p>
 *
 * <p>规则：</p>
 * <ul>
 *     <li><b>ASCII 字母数字串</b>：转为小写后整体成词。{@code "Spring AI 2.0"} → {@code ["spring", "ai", "2", "0"]}</li>
 *     <li><b>CJK 汉字</b>：同时产出 <b>unigram（单字）</b> 与 <b>bigram（二元组）</b>。
 *         {@code "知识平台"} → {@code ["知","识","平","台","知识","识平","平台"]}</li>
 * </ul>
 *
 * <p><b>为什么中文要 unigram + bigram 并存？</b></p>
 * <p>只用 bigram：查询「报销」能命中「报销流程」，但对单字查询（「税」）召回为零。
 * 只用 unigram：「知识平台」会被拆成毫不相关的四个字，噪声极大，精确率崩塌。
 * 两者并存 = 用 unigram 保召回、用 bigram 保精确，再由 BM25 的 IDF 自动为
 * 罕见 bigram 加权、为高频单字降权。<b>这不是妥协，而是让排序算法去做它擅长的事。</b></p>
 *
 * @author ZeroAi
 */
public final class TextTokenizer {

    private TextTokenizer() {
    }

    /**
     * 判断字符是否为 CJK 统一汉字。
     */
    private static boolean isCjk(char c) {
        return c >= '\u4E00' && c <= '\u9FFF';
    }

    /**
     * 分词。
     *
     * @param text 待分词文本，允许为 null（返回空列表）
     * @return token 列表，保留出现顺序与重复（BM25 需要词频）
     */
    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }

        StringBuilder ascii = new StringBuilder();
        StringBuilder cjk = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                flushAscii(ascii, tokens);
                cjk.append(c);
            } else if (isWordChar(c)) {
                flushCjk(cjk, tokens);
                ascii.append(c);
            } else {
                // 标点、空白、符号都作为分隔符
                flushAscii(ascii, tokens);
                flushCjk(cjk, tokens);
            }
        }
        flushAscii(ascii, tokens);
        flushCjk(cjk, tokens);
        return tokens;
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private static void flushAscii(StringBuilder buf, List<String> out) {
        if (!buf.isEmpty()) {
            out.add(buf.toString().toLowerCase(Locale.ROOT));
            buf.setLength(0);
        }
    }

    private static void flushCjk(StringBuilder buf, List<String> out) {
        if (buf.isEmpty()) {
            return;
        }
        String run = buf.toString();
        for (int i = 0; i < run.length(); i++) {
            out.add(run.substring(i, i + 1));
            if (i + 2 <= run.length()) {
                out.add(run.substring(i, i + 2));
            }
        }
        buf.setLength(0);
    }
}
