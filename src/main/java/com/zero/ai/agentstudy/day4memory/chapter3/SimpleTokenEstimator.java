package com.zero.ai.agentstudy.day4memory.chapter3;

import com.zero.ai.agentstudy.back.model.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 教学版 token 估算器。
 *
 * 真实项目应使用模型对应 tokenizer。这里为了可直接运行，用粗略规则估算：
 * - 中文字符约 1 token
 * - 英文按每 4 个字符约 1 token
 * - 每条 message 额外估算 4 token 作为 role/结构开销
 */
@Component
public class SimpleTokenEstimator {

    public int estimate(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage message : messages) {
            total += 4;
            total += estimate(message.getContent());
        }
        return total;
    }

    public int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int chinese = 0;
        int nonChinese = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isChinese(c)) {
                chinese++;
            } else if (!Character.isWhitespace(c)) {
                nonChinese++;
            }
        }
        return chinese + Math.max(1, (int) Math.ceil(nonChinese / 4.0));
    }

    private boolean isChinese(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A;
    }
}

