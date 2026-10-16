# 第 14 章：knowledge-admin —— 管理端与全链路复盘

> 配套代码：`admin/`
> 可运行 Demo：`demo/Chapter14Demo.java`

---

## 一、这一章要解决的问题

前 13 章建成了平台。但**平台不是部署完就不管的软件，而是需要持续运营的系统。**

运维需要回答的问题很多，而且都**无法靠"看代码"回答**：

- 这个租户现在有多少切片？配额还剩多少？
- Embedding 缓存命中率多少？有没有在重复烧钱？
- **向量库与倒排索引的数量一致吗？**（不一致说明历史上发生过双写失败）
- 谁在什么时候把机密库的权限授予了谁？

本章交付管理端能力，并做一次完整的全链路复盘，
**最后给出一份诚实的「教学实现 vs 生产实现」差距清单。**

---

## 二、核心设计一：管理端必须与业务 API 严格隔离

```java
@RestController
@RequestMapping("/api/knowledge/admin")
public class AdminController { ... }
```

**两层理由：**

### 2.1 权限隔离

授权操作要求 `MANAGE` 权限，远高于业务接口的 `SEARCH` / `WRITE`。

如果授权接口和普通接口混在一起、共用一套权限校验，
就很容易出现"为了图方便而放宽整体权限"的情况。

### 2.2 职责隔离（Separation of Duties）

**能创建知识库的人，不应该能给自己授权。**

否则任何有创建权限的人都能通过「创建 → 自授权」把权限体系架空。
现实中对应措施是：创建资源走业务系统，授权走独立的审批流程。

> 这正是第 13 章的 `KnowledgeWorkspaceController`（创建）
> 与本章的 `AdminController`（授权）必须分开的原因。

Demo 实测：

```
admin（对 policy 库有 ADMIN）把 secret 库的 READER 授给 guest：
    ACL 快照：AccessControlList[tenantId=acme, knowledgeBaseId=kb-secret,
              userRoles={admin=ADMIN, guest=READER}, groupRoles={}, publicRole=NONE]

再试一次越权授权：让 guest 去授权别人 ——
  ✓ 已拦截：主体 guest 无权对知识库 kb-secret 执行 MANAGE
```

---

## 三、核心设计二：授权必须留痕

```java
return KnowledgeResult.ok(Map.of(
        "operator", operator.userId(),
        "knowledgeBaseId", knowledgeBaseId,
        "grantedTo", "user:" + userId,
        "role", parsedRole.name(),
        "aclSnapshot", updated.toString()));
```

每一次授权都记录：**谁（operator）→ 对谁（target）→ 授予什么（role）**。

> **没有审计日志的权限系统，在出现泄漏事故时无法追溯——
> 你既不知道是谁开的口子，也不知道开给了谁、什么时候开的，
> 更不知道还有哪些口子开着。**

生产环境应写入**独立的、append-only 的审计表**。
这里返回 ACL 快照作为凭证，是最小实现。

---

## 四、核心设计三：平台统计与「索引一致性」这个最该被监控的指标

```java
public Map<String, Object> snapshot() {
    long vectorCount = vectorStore.count();
    int bm25Count = bm25.size();
    int registryCount = indexer.indexedChunkCount();

    boolean consistent = (vectorCount == bm25Count) && (bm25Count == registryCount);
    stats.put("indexConsistent", consistent);
    if (!consistent) {
        stats.put("inconsistencyHint", String.format(
                "向量库 %d / 倒排索引 %d / 反查表 %d —— 三者必须相等，"
                        + "不相等说明发生过双写失败，需要重新索引", ...));
    }
    // Embedding 成本指标
    stats.put("embeddingCacheHitRate", ...);
    return stats;
}
```

### 4.1 为什么 indexConsistent 是最该被监控的指标

第 7 章说过：**Hybrid Search 要求同一个切片同时存在于两套索引里**，
而双写天然有不一致的风险。

如果不主动监控，这种不一致会**一直潜伏**，
直到某天用户反馈"搜不到某份文档"才被发现——
**而那时已经无从追溯是哪一次写入失败导致的了。**

Demo 实测（人为制造一次双写失败）：

```
只删向量库、不删倒排索引（模拟一次双写失败）：
  isIndexConsistent() = false
  提示信息：向量库 2 / 倒排索引 6 / 反查表 6
           —— 三者必须相等，不相等说明发生过双写失败，需要重新索引
```

> **修复手段是重新索引该文档，而不是试图「手动补齐」某一套索引——
> 补齐只会掩盖问题。**

### 4.2 为什么还要统计 Embedding 缓存命中率

这是**成本可观测性**。命中率长期低于 10% 说明文档里重复内容少，
可考虑关闭缓存以节省内存；高于 60% 说明文档高度重复，
应该先做去重再入库。

**没有这个指标，你根本不知道钱花在哪了。**

---

## 五、全链路复盘：14 个模块如何串成一条线

```
  上传 ──▶ document    文档聚合、版本、生命周期状态机、租户隔离
    │
    ▼
  解析 ──▶ parser      PDF/Word/Excel/PPT/HTML/MD/TXT + OCR 端口
    │
    ▼
  切分 ──▶ chunk       定长 / 语义 / 结构化，切片自带租户与知识库维度
    │
    ▼
向量化 ──▶ embedding   批处理 + 去重缓存 + 模型锁版本（写入向量库）
    │
    ▼
  入库 ──▶ retriever   双写：向量库(稠密) + 倒排索引(稀疏 BM25)
    │
    ▼
  检索 ──▶ acl         ① 权限收敛出「可读知识库集合」
    │      retriever   ② 只在可读范围内做混合召回
    │      fusion      ③ RRF 融合（只认排名，不认分数）
    │      reranker    ④ 精排 + MMR 多样性去重
    ▼
  结果 ──▶ api         带 citation 的可溯源结果，供各 Agent 调用
    │
    ▼
  运维 ──▶ sync        增量同步 / 删除同步（幽灵知识防护）
          browser     网页采集连接器
          admin       授权审计 / 统计 / 一致性检查
```

### 5.1 各章的知识点索引

| 章 | 模块 | 一句话核心 |
| --- | --- | --- |
| 1 | core | 强类型 ID + TenantContext，多租户的第一道防线 |
| 2 | 架构 | 14 个模块、严格单向依赖 |
| 3 | document | 聚合根守护不变量，状态只按状态机流转 |
| 4 | parser | 策略模式 + 先剔除 script/style，否则污染向量语义 |
| 5 | chunk | 切片自带租户与知识库维度，供检索下推过滤 |
| 6 | embedding | 模型锁版本（向量漂移防线）+ 批处理 + 去重缓存 |
| 7 | retriever | Hybrid（向量 + BM25）+ RRF（只认排名）+ 权限前置 |
| 8 | reranker | 两阶段架构 + MMR 解决重复内容挤占上下文 |
| 9 | workspace | 三层结构，租户只从上下文取 |
| 10 | acl | 默认拒绝 + 跨租户校验 + 权限前置到召回 |
| 11 | sync | 内容指纹 + 三个动作缺一不可（DELETE 最易漏） |
| 12 | browser | 失败是返回值 + 接入端千变万化、处理端只有一条 |
| 13 | api | 门面收口 + 先删再写 + citation 溯源 |
| 14 | admin | 职责分离 + 授权留痕 + 索引一致性监控 |

---

## 六、诚实清单：教学实现与生产实现的差距

> **以下 10 条都不是生产实现，上线前必须逐项替换。**

| # | 教学实现 | 生产要求 | 风险 |
| --- | --- | --- | --- |
| 1 | 存储全部为内存 | PostgreSQL + pgvector | 进程重启即丢失 |
| 2 | 向量检索暴力扫描 O(n·d) | HNSW / IVFFlat 索引 | 百万级不可用；换索引前先用暴力扫描跑基线 |
| 3 | BM25 删除靠全量重建 O(n) | Lucene/Elasticsearch（墓碑 + 段合并） | 删除时加锁，大库卡顿 |
| 4 | Embedding 用 HashingModel | 真实稠密模型（bge/nomic/text-embedding-3） | 只是字面相似，**不是语义相似** |
| 5 | 分词是 CJK unigram+bigram | 带词典的分词器 | 需与离线索引用同一套分词，否则质量莫名下降 |
| 6 | Reranker 用 Heuristic | Cross-Encoder | 精排精度差距大 |
| 7 | 无最小相似度阈值 | 配置 minScore（0.7~0.75 起步） | **向量检索没有零命中**，会返回一堆无关内容 |
| 8 | 双写非原子 | 事件驱动 + 最终一致 | 一处写成功一处失败 → 永久不一致 |
| 9 | 入库是同步的 | 异步化：接口返回 202 + 消息队列 | 接口超时、连接耗尽 |
| 10 | 租户/身份来自请求头 | JWT / OAuth2 / 会话 | **可被伪造，隔离形同虚设** |

### 6.1 第 4 条需要特别强调

`HashingEmbeddingModel` 是**字面相似**，不是语义相似。
「远程办公」与「弹性工作制」在字面上毫无重叠，它的相似度接近 0。

**这不是缺陷，而是刻意保留的教学设计**：
它用一个可运行的事实告诉你——
生产环境必须用真实模型，而且只靠向量同样不够（所以才有 Hybrid）。

### 6.2 第 8 条的升级路径

双写无法保证原子性：向量库写成功、倒排索引写失败，就永久不一致了。

**生产方案是事件驱动 + 最终一致**：
写入向量库后发领域事件 → 消费者更新倒排索引 → 失败进重试队列。

这与第 11 章处理「删除同步」的思路是相通的，
**本质都是"用事件驱动保证最终一致"**。

---

## 七、运行 Demo

```bash
java -cp target/classes:<依赖> com.zero.ai.agentstudy.day15knowledgeplatform.demo.Chapter14Demo
```

真实输出（节选）：

```
[2] 管理端：授权必须留痕，且需要 MANAGE 权限
  ACL 快照：AccessControlList[tenantId=acme, knowledgeBaseId=kb-secret,
            userRoles={admin=ADMIN, guest=READER}, groupRoles={}, publicRole=NONE]
  ✓ 已拦截：主体 guest 无权对知识库 kb-secret 执行 MANAGE

[3] 管理端：平台统计与索引一致性检查
  授权生效后，guest 现在可以检索之前无权访问的机密库：
    SearchOutcome{hits=2, scope=2, reranker=heuristic, elapsed=17ms}

  平台统计快照：
    vectorCount: 6    bm25Count: 6    registryCount: 6
    indexConsistent: true
    embeddingCacheHitRate: 0.0%

[4] 一致性检查：人为制造不一致，验证能否被发现
  isIndexConsistent() = false
  提示信息：向量库 2 / 倒排索引 6 / 反查表 6 —— 需重新索引
```

---

## 八、Day15 十四个避坑总表

| # | 坑 | 后果 | 防护位置 |
| --- | --- | --- | --- |
| 1 | Chunk 太大 | 向量被稀释，抓不住重点 | 第 5 章策略参数 |
| 2 | Chunk 太小 | 语义割裂，答案支离破碎 | 第 5 章策略参数 |
| 3 | **Embedding 换模型不重建** | 向量漂移，检索静默崩塌 | 第 6 章 `requireComparable` |
| 4 | **权限泄漏** | 合规事故，可以上新闻 | 第 10 章默认拒绝 + 前置过滤 |
| 5 | Metadata 缺失 | 无法过滤、审计、溯源 | 第 5 章切片维度 |
| 6 | **删除不同步** | 幽灵知识，可能泄漏已删敏感信息 | 第 11 章 DELETE 动作 |
| 7 | 只用向量不用 BM25 | 型号/代码/专名大量漏召回 | 第 7 章 Hybrid |
| 8 | 不做 Reranker | 噪声混进 Prompt，答案质量差 | 第 8 章两阶段 |
| 9 | 忽视召回率监控 | 质量下降无人知晓，用户默默流失 | 第 14 章统计 |
| 10 | 同步阻塞主流程 | 接口超时、连接耗尽 | 异步化 + 队列 |
| 11 | **更新不同步** | 新旧两版内容同时被检索到 | 第 13 章 `reingest` + 先删再写 |
| 12 | **向量检索无零命中** | 问什么都返回一堆无关内容 | minScore 阈值 |
| 13 | **双写不一致** | 只在特定检索路径暴露，极难复现 | 第 14 章 indexConsistent 监控 |
| 14 | **Filter 不清理 ThreadLocal** | 线程复用导致租户串号 | `finally { clear(); }` |

---

## 九、面试题

**Q1：企业知识平台上线后，你会监控哪些指标？**

> 分四类：
> 1. **一致性**（最重要）：向量库 / 倒排索引 / 反查表三者条数必须相等。
>    不等说明发生过双写失败，需要重新索引；
> 2. **检索质量**：召回率（正确答案是否在候选里）、
>    多路命中占比（低于正常值说明两路结果无交集，策略可能有问题）、
>    零结果率（突然升高说明索引或权限配置出了问题）；
> 3. **成本**：Embedding 调用量、缓存命中率（低于 10% 可关缓存，
>    高于 60% 说明文档高度重复，应先去重）、Reranker 调用量；
> 4. **性能**：各阶段耗时（解析/切分/向量化/检索/精排），
>    用于定位瓶颈。

**Q2：发现向量库与倒排索引数量不一致，怎么处理？**

> **重新索引受影响的文档，而不是试图「手动补齐」某一套索引。**
>
> 手动补齐只能修好数字，但无法确认补齐的内容是否正确，
> 而且会掩盖"为什么会不一致"这个根本问题
> （是双写代码有 bug？是某次异常导致中断？）。
>
> 正确流程：
> 1. 定位哪些文档缺失（对比三处条目）；
> 2. 触发这些文档的重新索引；
> 3. 排查双写失败的根因（生产应改为事件驱动 + 最终一致）；
> 4. 把"三处条数相等"做成常驻监控，而不是等出问题再查。

**Q3：如果让你把这套教学实现推上生产，最优先替换哪三项？**

> 1. **租户与身份来源**——请求头可伪造，这是**安全性问题**，
>    必须最先改（用 JWT/OAuth2），否则其他一切都建立在漏洞之上；
> 2. **持久化 + 索引**——内存存储重启即丢、暴力扫描撑不住百万级、
>    BM25 删除全量重建会卡顿，这是**可用性与性能问题**；
> 3. **真实 Embedding 模型 + minScore**——
>    HashingModel 只是字面相似，minScore 缺失会导致"问什么都有一堆无关结果"，
>    这是**质量问题**，直接影响用户对产品的信任。
>
> 排序原则是：**先安全，再可用，后质量**。

---

## 十、Day15 总结

从 Day5 的「玩具 RAG」到今天的「企业级知识平台」，我们完成了这些跨越：

| 维度 | Day5 的 RAG Demo | Day15 的知识平台 |
| --- | --- | --- |
| 数据接入 | 手动喂 TXT | 上传 PDF/Word/Excel/PPT + 网页采集 + OCR 端口 |
| 检索方式 | 只有向量 | Vector + BM25 + RRF 融合 + Reranker + MMR |
| 多租户 | 无 | Tenant / Workspace / KnowledgeBase 三层隔离 |
| 权限 | 无 | 默认拒绝 + 跨租户校验 + 权限前置到召回 |
| 数据一致性 | 无 | 版本 + 增量同步 + 删除同步 + 更新同步 |
| 治理 | 无 | Metadata、标签、ACL 审计、统计 |
| 可观测 | 无 | 各阶段耗时、缓存命中率、索引一致性、多路命中 |
| 被消费 | 单点问答 | 统一 API，供 Office/WeChat/客服/搜索 Agent 调用 |

> **RAG 是引擎，Knowledge Platform 是整辆车。**
> 你不能把一台发动机卖给客户当汽车用。

而贯穿全书的一条主线是：

> **企业级的本质，不是"功能更多"，而是"每一种失败都被预见并妥善处理"。**
> 权限泄漏、向量漂移、幽灵知识、双写不一致、租户串号——
> 这些才是知识平台真正的工程量所在。

---

## 十一、课后练习（⭐ 必做 / ⭐⭐ 进阶 / ⭐⭐⭐ 企业挑战）

**⭐ 必做**

1. 把 `HashingEmbeddingModel` 换成 `SpringAiEmbeddingModel`，
   观察检索质量的变化（尤其是同义改写类查询）；
2. 给 `SearchQuery` 加上 `minScore`，观察"无关查询不再返回结果"；
3. 把 `InMemoryVectorStore` 换成一个简单的文件持久化实现。

**⭐⭐ 进阶**

4. 实现 `PgVectorStore`（端口已留好），并对比暴力扫描的召回率；
5. 把双写改造成事件驱动（写向量库后发事件，消费者更新倒排索引）；
6. 实现网页采集的「HTTP → 无头浏览器」自动降级链；
7. 给 Reranker 加 A/B 对比框架，用 NDCG@5 评估提升幅度。

**⭐⭐⭐ 企业挑战**

8. 实现文档级细粒度 ACL，并评估它对检索性能的影响；
9. 设计一个支持"全量重建 + 双写 + 灰度切换"的 Embedding 模型升级方案；
10. 接入真实数据源连接器（Confluence / 企业微信文档 / Git 仓库），
    验证"接入端千变万化、处理端只有一条"；
11. 设计按"席位 / 文档量 / 检索次数"计费的配额与限流体系。

---

> **Day15 全 14 章完结。**
>
> 至此，`knowledge-core` / `document` / `parser` / `chunk` / `embedding` /
> `retriever` / `reranker` / `workspace` / `acl` / `sync` / `browser` /
> `api` / `admin` 共 13 个模块全部实现，并附带 9 个可运行的 Demo。
>
> 下一步：继续 **Day16：知识平台进阶（Hybrid 检索调优与评测体系）**。
