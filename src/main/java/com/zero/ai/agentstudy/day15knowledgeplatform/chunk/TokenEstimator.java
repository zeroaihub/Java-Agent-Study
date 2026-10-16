package com.zero.ai.agentstudy.day15knowledgeplatform.chunk;

/**
 * Token 估算器。
 *
 * <p>为什么需要估算而不用精确计算？精确计算要加载对应模型的 tokenizer
 * （如 tiktoken / jtokkit），在切分阶段为每段文本都跑一遍太重。
 * 切分阶段只需要一个<b>够用的近似值</b>来回答两个问题：</p>
 * <ol>
 *     <li>这个 Chunk 会不会超长（撑爆上下文窗口）？</li>
 *     <li>Prompt 里塞 N 个 Chunk 大约占多少预算？</li>
 * </ol>
 *
 * <p>估算规则（经验值）：</p>
 * <ul>
 *     <li>中日韩字符：约 1 token / 字</li>
 *     <li>其他（拉丁字母、数字、空格）：约 4 字符 / token</li>
 * </ul>
 *
 * <p>注意：这是<b>保守偏大</b>的估算，宁可估多一点触发切分，也不要估少了把超长片段塞进 Prompt。</p>
 *
 * @author ZeroAi
 */
public final class TokenEstimator {

    private static final double CHARS_PER_TOKEN_NON_CJK = 4.0;

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjk++;
            } else {
                other++;
            }
        }
        double tokens = cjk + other / CHARS_PER_TOKEN_NON_CJK;
        // 向上取整并至少为 1
        return Math.max(1, (int) Math.ceil(tokens));
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES;
    }
}
