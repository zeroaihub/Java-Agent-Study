package com.zero.ai.agentstudy.day15knowledgeplatform.demo;

import com.zero.ai.agentstudy.day15knowledgeplatform.browser.CrawlService;
import com.zero.ai.agentstudy.day15knowledgeplatform.browser.adapter.JdkWebFetcher;
import com.zero.ai.agentstudy.day15knowledgeplatform.browser.port.WebFetcher;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.DocumentId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.sync.IncrementalSyncService;
import com.zero.ai.agentstudy.day15knowledgeplatform.sync.model.ContentFingerprint;
import com.zero.ai.agentstudy.day15knowledgeplatform.sync.model.SyncJob;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 第 11-12 章 Demo：增量同步、删除同步与网页采集。
 *
 * <p>第 11 章的重点是那条<b>最容易被漏掉、也最危险</b>的规则：
 * 同步 = 让本地与源端一致，而不只是「把新的拿过来」。
 * 少了删除这一步，就会制造「幽灵知识」。</p>
 *
 * @author ZeroAi
 */
public class Chapter11Demo {

    public static void main(String[] args) {
        System.out.println("=".repeat(78));
        System.out.println("  第 11-12 章：knowledge-sync + knowledge-browser");
        System.out.println("  增量同步、删除同步与网页采集");
        System.out.println("=".repeat(78));

        TenantId tenant = TenantId.of("acme");
        KnowledgeBaseId kb = KnowledgeBaseId.of("kb-policy");

        // ---------- 第 11 章 ----------
        printSection("第 11 章 [1] 内容指纹：为什么不能用「最后修改时间」");

        String doc = "差旅报销标准 v1";
        ContentFingerprint f1 = ContentFingerprint.ofText(doc);
        ContentFingerprint f2 = ContentFingerprint.ofText(doc);
        ContentFingerprint f3 = ContentFingerprint.ofText("差旅报销标准 v2");

        System.out.println("  文本「" + doc + "」");
        System.out.println("    指纹 f1 = " + f1.shortValue() + "…");
        System.out.println("  同样的文本再算一次");
        System.out.println("    指纹 f2 = " + f2.shortValue() + "…  equals(f1) = " + f1.equals(f2));
        System.out.println("  文本改成「差旅报销标准 v2」");
        System.out.println("    指纹 f3 = " + f3.shortValue() + "…  equals(f1) = " + f1.equals(f3));
        System.out.println();
        System.out.println("  ★ mtime（最后修改时间）做不到这种确定性：");
        System.out.println("    文件复制、解压、跨系统同步都会改变 mtime，内容却一字未改；");
        System.out.println("    不同机器时钟不同步，跨平台也对不上。");
        System.out.println("    用 mtime 的后果是「每次同步都全量重跑」，白烧 Embedding 的钱。");

        printSection("第 11 章 [2] 首次同步：全部是 ADD");

        var sync = new IncrementalSyncService();

        Map<String, IncrementalSyncService.SourceItem> source = new LinkedHashMap<>();
        source.put("policy/reimburse.md",
                new IncrementalSyncService.SourceItem(ContentFingerprint.ofText("报销标准 A"), null));
        source.put("policy/travel.md",
                new IncrementalSyncService.SourceItem(ContentFingerprint.ofText("差旅标准 A"), null));
        source.put("policy/invoice.md",
                new IncrementalSyncService.SourceItem(ContentFingerprint.ofText("发票要求 A"), null));

        List<SyncJob> jobs = sync.planJobs(tenant, kb, source);
        int addCount = (int) jobs.stream().filter(j -> j.action() == SyncJob.Action.ADD).count();
        System.out.println("  源端 3 篇，本地 0 篇 → 计划 " + jobs.size() + " 个任务，其中 ADD " + addCount + " 个");

        // 标记成功并落库
        for (SyncJob j : jobs) {
            j.markRunning();
            j.markSucceeded("已入库，documentId=" + "doc-" + j.sourceKey().hashCode());
            // 真实场景下 documentId 来自入库结果，这里构造一个
        }
        sync.commit(tenant, kb, rebind(jobs));
        System.out.println("  提交后，本地已同步条目数：" + sync.syncedCount());

        printSection("第 11 章 [3] 第二次同步：只改了 1 篇，删了 1 篇");

        Map<String, IncrementalSyncService.SourceItem> source2 = new LinkedHashMap<>();
        source2.put("policy/reimburse.md",
                new IncrementalSyncService.SourceItem(ContentFingerprint.ofText("报销标准 B"), null)); // 改了
        source2.put("policy/travel.md",
                new IncrementalSyncService.SourceItem(ContentFingerprint.ofText("差旅标准 A"), null)); // 没变
        // policy/invoice.md 在源端被删除了

        List<SyncJob> jobs2 = sync.planJobs(tenant, kb, source2);
        System.out.println("  源端现状：reimburse 已改，travel 未变，invoice 已删除");
        System.out.println();
        System.out.println("  生成的同步计划：");
        for (SyncJob j : jobs2) {
            System.out.printf("    %-8s %s%n", j.action(), j.sourceKey());
        }
        System.out.println();

        long updateCount = jobs2.stream().filter(j -> j.action() == SyncJob.Action.UPDATE).count();
        long deleteCount = jobs2.stream().filter(j -> j.action() == SyncJob.Action.DELETE).count();
        long skipCount = jobs2.stream().filter(j -> j.action() == SyncJob.Action.SKIP).count();

        System.out.println("  统计：UPDATE=" + updateCount + "，DELETE=" + deleteCount
                + "，SKIP=" + skipCount + "（SKIP 不生成任务，这里为 0）");
        System.out.println();
        System.out.println("  ★ 注意 travel.md 没有出现在计划里——指纹一致，直接跳过。");
        System.out.println("    10 万篇文档里每天只动 200 篇，就只处理这 200 篇。");
        System.out.println("    相比全量重建，这是两个数量级的成本差距。");
        System.out.println();
        System.out.println("  ★★ 更要注意 DELETE 这一条：");
        System.out.println("    它靠的是「反查本地状态表」——本地有记录但源端没有了。");
        System.out.println("    这一步是绝大多数人漏掉的，也是「幽灵知识」的根源：");
        System.out.println("    源端删了，向量还在库里，用户依然能问出那段本该消失的内容。");

        printSection("第 11 章 [4] 提交删除后：状态表必须真的清掉");
        for (SyncJob j : jobs2) {
            j.markRunning();
            j.markSucceeded("完成");
        }
        sync.commit(tenant, kb, rebind(jobs2));
        System.out.println("  提交后，本地已同步条目数：" + sync.syncedCount() + "（应为 2）");
        System.out.println();
        System.out.println("  ★ DELETE 必须在状态表里真的 remove，而不只是标记。");
        System.out.println("    否则下次扫描时，这条记录又会被判成 ADD，");
        System.out.println("    于是「删除 → 重新入库 → 再判定删除」无限循环。");

        // ---------- 第 12 章 ----------
        printSection("第 12 章：网页采集（抓取 → 解析 → 汇入同一条入库链路）");

        WebFetcher fetcher = new JdkWebFetcher();
        CrawlService crawler = new CrawlService(fetcher);

        List<String> urls = List.of(
                "https://example.com",
                "https://this-domain-does-not-exist-xyz123.com",
                "not-a-valid-url");

        for (String url : urls) {
            CrawlService.CrawlResult r = crawler.crawl(url);
            System.out.printf("  %-52s %s%n", url,
                    r.success() ? "HTTP " + r.statusCode() + " | " + r.preview(46)
                                : "失败：" + r.error());
        }

        System.out.println();
        System.out.println("  ★ 采集失败返回「结果对象」而不是抛异常——");
        System.out.println("    批量采集 1000 个页面时，3 个 404 不该让另外 997 个陪葬。");
        System.out.println();
        System.out.println("  ★ 采集与「上传文件」最终汇入同一条解析/切分/向量化流水线：");
        System.out.println("    URL → fetch() → HTML → HtmlParser → ParsedDocument → 第 3~7 章的入库链路");
        System.out.println("    这就是「连接器生态」的价值：接入端千变万化，处理端只有一条。");
        System.out.println();
        System.out.println("  注：本 Demo 需要网络访问；若处于离线环境，抓取会失败，");
        System.out.println("      但失败路径本身正是要演示的内容之一。");

        System.out.println();
        System.out.println("=".repeat(78));
        System.out.println("  第 11-12 章 Demo 结束");
        System.out.println("=".repeat(78));
    }

    /**
     * 为同步任务补上真实的 documentId（真实场景由入库结果提供）。
     */
    private static List<SyncJob> rebind(List<SyncJob> jobs) {
        return jobs.stream().map(j -> SyncJob.of(j.tenantId(), j.knowledgeBaseId(),
                DocumentId.of("doc-" + Math.abs(j.sourceKey().hashCode())),
                j.sourceKey(), j.action(), j.fingerprint())).peek(j -> {
            j.markRunning();
            j.markSucceeded("完成");
        }).toList();
    }

    private static void printSection(String title) {
        System.out.println();
        System.out.println("-".repeat(78));
        System.out.println(title);
        System.out.println("-".repeat(78));
    }
}
