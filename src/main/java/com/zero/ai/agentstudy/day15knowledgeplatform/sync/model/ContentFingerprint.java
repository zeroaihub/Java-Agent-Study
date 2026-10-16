package com.zero.ai.agentstudy.day15knowledgeplatform.sync.model;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 内容指纹（增量同步的核心）。
 *
 * <p><b>它解决什么问题？</b>
 * 「文档有没有变」。增量同步的全部前提，就是能廉价、可靠地回答这个问题。
 * 如果判断错了，后果是两个方向都很糟：</p>
 * <ul>
 *     <li><b>误判为"没变"（漏检）</b>：内容其实改了，但没重新入库 → 检索到过期知识；</li>
 *     <li><b>误判为"变了"（误检）</b>：没变却重新跑一遍全流水线 → 白白烧掉 Embedding 的钱。</li>
 * </ul>
 *
 * <p><b>为什么不用「最后修改时间」？</b>
 * 这是最常见的错误选择。mtime 有三宗罪：</p>
 * <ol>
 *     <li><b>不可靠</b>：文件复制、解压、跨系统同步都会改变 mtime，内容却一字未改；</li>
 *     <li><b>精度不够</b>：某些文件系统的时间戳只到秒级，一秒内的连续修改无法区分；</li>
 *     <li><b>不可比</b>：不同系统的时钟不同步，跨平台对不上。</li>
 * </ol>
 * <b>内容指纹是对内容本身做哈希，改一个字节就变，没改就绝不会变——
 * 这才是"有没有变"这个问题该有的答案。</b></p>
 *
 * <p><b>为什么选 SHA-256 而不是 MD5？</b>
 * MD5 已经被攻破（可构造碰撞），虽然对"判断内容是否变化"这个场景
 * 碰撞的概率仍极低，但用 SHA-256 没有任何额外代价（现代 CPU 有硬件加速），
 * <b>没有理由选择一个已知不安全的算法</b>。这是安全工程里的基本原则：
 * 默认选择当前被认为安全的算法。</p>
 *
 * @param value 十六进制指纹
 * @author ZeroAi
 */
public record ContentFingerprint(String value) {

    public ContentFingerprint {
        if (value == null || value.isBlank()) {
            throw new KnowledgeException(KnowledgeErrorCode.INVALID_ARGUMENT, "内容指纹不能为空");
        }
    }

    /**
     * 对字节内容计算 SHA-256 指纹。
     */
    public static ContentFingerprint ofBytes(byte[] content) {
        KnowledgeException.requireNonNull(content, "content");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            return new ContentFingerprint(toHex(hash));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是 JDK 规范强制实现的算法，理论上不可达
            throw new KnowledgeException(KnowledgeErrorCode.SYNC_FAILED,
                    "SHA-256 算法不可用：" + ex.getMessage(), ex);
        }
    }

    /**
     * 对文本计算指纹。
     *
     * <p>注意：这里显式指定 UTF-8 而非使用平台默认编码。
     * 用 {@code String.getBytes()} 而不带字符集，是 Java 里最经典的跨平台陷阱之一——
     * 开发环境（UTF-8）与生产容器（可能是 POSIX/ANSI）算出不同指纹，
     * 表现为"本地测得好好的，上了生产每次同步都全量重跑"。</p>
     */
    public static ContentFingerprint ofText(String text) {
        KnowledgeException.requireNonNull(text, "text");
        return ofBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 短路指纹：前 16 位十六进制（用于日志展示，降低阅读负担）。
     */
    public String shortValue() {
        return value.length() <= 16 ? value : value.substring(0, 16);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
