package com.zero.ai.agentstudy.day15knowledgeplatform.demo;

import com.zero.ai.agentstudy.day15knowledgeplatform.chunk.model.Chunk;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.ChunkId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.DocumentId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.Metadata;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.WorkspaceId;
import com.zero.ai.agentstudy.day15knowledgeplatform.embedding.EmbeddingService;
import com.zero.ai.agentstudy.day15knowledgeplatform.embedding.adapter.HashingEmbeddingModel;
import com.zero.ai.agentstudy.day15knowledgeplatform.embedding.model.EmbeddedChunk;
import com.zero.ai.agentstudy.day15knowledgeplatform.embedding.model.EmbeddingVector;
import com.zero.ai.agentstudy.day15knowledgeplatform.embedding.port.EmbeddingModel;

import java.util.List;

/**
 * 第 6 章 Demo：向量化与「模型锁版本」铁律。
 *
 * <p>演示四件事：</p>
 * <ol>
 *     <li>文本如何变成定长向量，以及相似文本的向量确实更接近；</li>
 *     <li>批处理把多次网络往返压成一次；</li>
 *     <li>重复文本的缓存命中如何省钱；</li>
 *     <li><b>不同模型的向量禁止比较</b>——这是「向量漂移」的防线。</li>
 * </ol>
 *
 * @author ZeroAi
 */
public class Chapter06Demo {

    public static void main(String[] args) {
        printHeader("第 6 章：knowledge-embedding —— 向量化与模型锁版本");

        // ---------- 1. 文本 → 向量 ----------
        printSection("[1] 文本向量化：相似文本的向量确实更接近");

        EmbeddingModel model = new HashingEmbeddingModel(256);
        EmbeddingService service = new EmbeddingService(model, 16);

        String a = "员工出差后如何报销差旅费用";
        String b = "员工出差后怎样报销差旅费用";   // 与 a 高度相似
        String c = "公司年度团建活动安排通知";     // 与 a 无关

        EmbeddingVector va = service.embedText(a);
        EmbeddingVector vb = service.embedText(b);
        EmbeddingVector vc = service.embedText(c);

        System.out.println("模型：" + model.modelId() + "，维度：" + model.dimension());
        System.out.println("  文本 A：" + a);
        System.out.println("  文本 B：" + b);
        System.out.println("  文本 C：" + c);
        System.out.println();
        System.out.printf("  sim(A, B) = %.4f   ← 近似同义句，应当较高%n", va.cosineSimilarity(vb));
        System.out.printf("  sim(A, C) = %.4f   ← 主题无关，应当较低%n", va.cosineSimilarity(vc));
        System.out.println();
        System.out.println("  结论：sim(A,B) 明显高于 sim(A,C)，说明向量确实刻画了文本相似性。");
        System.out.println("  注意：本实现是「字面相似」而非「语义相似」（见 HashingEmbeddingModel 注释），");
        System.out.println("        所以 A 与 B 靠的是字面高度重合。真正的语义模型能做到");
        System.out.println("        「远程办公」≈ 「弹性工作制」这种无字面重合的匹配。");

        // ---------- 2. 向量已经归一化 ----------
        printSection("[2] 归一化：余弦相似度退化为点积");

        float[] values = va.values();
        double norm = 0.0;
        for (float v : values) {
            norm += (double) v * v;
        }
        System.out.printf("  向量模长 ||v|| = %.6f（应为 1.0）%n", Math.sqrt(norm));
        System.out.println("  归一化后 cosine(a,b) == dot(a,b)，百万级检索时可省掉大量浮点运算。");

        // ---------- 3. 批处理 + 缓存 ----------
        printSection("[3] 批处理与去重缓存：Embedding 阶段省钱的两个抓手");

        EmbeddingService fresh = new EmbeddingService(new HashingEmbeddingModel(256), 8);
        List<Chunk> chunks = buildChunks(30);
        System.out.println("  构造 " + chunks.size() + " 个切片，其中多处文本刻意重复");

        List<EmbeddedChunk> embedded = fresh.embedAll(chunks);
        System.out.println("  向量化完成：" + embedded.size() + " 个 EmbeddedChunk");
        System.out.println("  实际调用模型次数：" + fresh.batchInvocations() + " 次");
        System.out.println("    → 30 条文本里只有 4 条是不同内容，" + "所以真正付费的只有这 4 条");
        System.out.printf("  缓存命中率：%.1f%%（重复文本被复用，省下真实的模型调用费）%n",
                fresh.cacheHitRate() * 100);
        System.out.println("  生产提示：若实际缓存命中率长期低于 10%，");
        System.out.println("            说明文档里重复内容少，可考虑关闭缓存以节省内存；");
        System.out.println("            若高于 60%，说明文档高度重复，应先做去重再入库。");

        // ---------- 4. 模型锁版本 ----------
        printSection("[4] 模型锁版本：不同模型的向量禁止比较（fail-fast）");

        EmbeddingModel another = new HashingEmbeddingModel(256) {
            @Override
            public String modelId() {
                return "another-model-v2";
            }
        };
        EmbeddingVector fromAnother = another.embed(a);

        try {
            va.cosineSimilarity(fromAnother);
            System.out.println("  ✗ 未拦截，这是严重缺陷");
        } catch (RuntimeException ex) {
            System.out.println("  ✓ 已拦截跨模型比较：" + ex.getMessage());
        }

        // 维度不一致
        EmbeddingVector differentDim = new HashingEmbeddingModel(128).embed(a);
        try {
            va.cosineSimilarity(differentDim);
            System.out.println("  ✗ 未拦截，这是严重缺陷");
        } catch (RuntimeException ex) {
            System.out.println("  ✓ 已拦截跨维度比较：" + ex.getMessage());
        }

        System.out.println();
        System.out.println("  这条铁律的价值在于：宁可启动就崩，也不要线上静默返回垃圾。");
        System.out.println("  更换 Embedding 模型的正确姿势：新建库 → 双写 → 验证 → 切换。");

        printFooter();
    }

    private static List<Chunk> buildChunks(int count) {
        TenantId tenant = TenantId.of("acme");
        WorkspaceId ws = WorkspaceId.of("ws-finance");
        KnowledgeBaseId kb = KnowledgeBaseId.of("kb-policy");
        DocumentId doc = DocumentId.of("doc-reimburse-v3");

        String[] base = {
                "员工出差返回后需在 30 个工作日内提交差旅报销申请。",
                "报销单需附发票原件、行程单与审批记录。",
                "单笔超过 5000 元的报销需部门负责人与财务总监双签。",
                "本制度自发布之日起施行，由财务部负责解释。"
        };

        List<Chunk> out = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            // 前 4 条之后全部循环复用前面完全相同的文本。
            // 注意：必须是「完全相同」的文本才能命中缓存——
            // 哪怕只是加个序号后缀，指纹就变了，缓存也就失效了。
            // 现实中页眉页脚、免责声明这类内容正是靠这一点省下钱的。
            String text = base[i % base.length];
            out.add(new Chunk(ChunkId.newId(), tenant, ws, kb, doc, i, text,
                    text.length(), text.length() / 2,
                    Metadata.of(java.util.Map.of("documentTitle", "差旅报销管理制度"))));
        }
        return out;
    }

    private static void printHeader(String title) {
        System.out.println("=".repeat(78));
        System.out.println("  " + title);
        System.out.println("=".repeat(78));
    }

    private static void printSection(String title) {
        System.out.println();
        System.out.println("-".repeat(78));
        System.out.println(title);
        System.out.println("-".repeat(78));
    }

    private static void printFooter() {
        System.out.println();
        System.out.println("=".repeat(78));
        System.out.println("  第 6 章 Demo 结束");
        System.out.println("=".repeat(78));
    }
}
