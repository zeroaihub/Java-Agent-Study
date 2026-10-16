# 第 7 章：knowledge-retriever —— Hybrid Search 与 RRF 融合

> 配套代码：`retriever/`、`vectorstore/`、`core/text/TextTokenizer.java`
> 可运行 Demo：`demo/Chapter07Demo.java`

---

## 一、这一章要解决的问题

第 6 章我们把文本变成了向量。现在可以"检索"了——
把查询也变成向量，在库里找余弦相似度最高的几条。

**但如果只这么做，你的检索系统在企业环境里会立刻暴露三个硬伤：**

1. **精确 token 匹配不了**：用户搜 `KP-4001`、`X-2000`、`cosineSimilarity`，
   向量会把它们泛化掉，返回一堆"语义相关但完全不是用户要的"结果；
2. **低频专有名词匹配不了**：产品型号、内部黑话、新人名，
   训练语料里没见过，向量质量差；
3. **没有「零命中」的概念**：哪怕库里全是无关内容，
   "最近邻"也一定存在，它一定会返回点什么——
   **这就意味着「问什么都有一堆结果」，而这些噪声会污染 LLM 的上下文**。

反过来，**纯关键词检索（BM25）也不行**：同义改写、口语化提问、
跨语言，它一概不会。

> **结论：向量与关键词不是二选一，而是必须 Hybrid。**
> 这是 Google Vertex AI Search、Azure AI Search、Glean、OpenAI 的**共同共识**。

本章要交付的能力：

```
查询文本 ──┬─▶ DenseRetriever（向量）──┐
           │                          ├─▶ RRF 融合 ──▶ 候选结果
           └─▶ SparseRetriever（BM25）┘
                    ▲
                    └── 权限/作用域过滤必须前置到这里（第 10 章）
```

---

## 二、核心组件一：VectorStore 端口 + 内存实现

### 2.1 接口设计的关键：过滤条件是一等参数

```java
List<VectorMatch> search(TenantId tenantId,
                         Set<KnowledgeBaseId> knowledgeBaseIds,
                         EmbeddingVector query,
                         int topK);
```

**注意 `tenantId` 和 `knowledgeBaseIds` 是方法签名的一部分，而不是"先查全量再过滤"。**

这是「权限与作用域前置」原则在存储层的落地，原因有二：

- **正确性**：近似最近邻（HNSW/IVFFlat）返回的是**全局 Top-K**。
  如果先取全局 Top-100 再过滤掉 95 条无权的，
  真正属于你、排在 101 名的正确结果就**永远找不回来了**——召回率凭空损失。
- **安全**：后置过滤意味着无权数据曾短暂存在于应用内存中，
  日志、异常栈、序列化缓存任何一处疏忽都会造成泄漏。

### 2.2 为什么敢用暴力扫描（brute-force）

因为它算出的是**精确**最近邻，而 pgvector 的 HNSW 是**近似**的。

在教学与测试场景里，精确结果才是可信的基线（golden baseline）——
你无法判断「召回变差了」是因为算法错，
还是因为 ANN 索引的召回率本来就不到 100%。

> **生产换 HNSW 时的正确姿势**：先用本实现跑出基线，
> 再对比 ANN 的召回率损失（recall@k），确认损失在可接受范围内。

### 2.3 过滤要在算相似度之前

```java
for (EmbeddedChunk c : store.values()) {
    // 先过滤（廉价字段比较），再算相似度（O(d) 浮点运算）
    if (!c.chunk().tenantId().equals(tenantId)) continue;
    if (scopeLimited && !knowledgeBaseIds.contains(c.chunk().knowledgeBaseId())) continue;
    double score = c.vector().cosineSimilarity(query);
    matches.add(new VectorMatch(c, score));
}
```

顺序很重要：过滤是廉价的字段比较，相似度是 O(d) 的浮点运算。
**先过滤可以省掉绝大部分无效计算**，在向量库里就是真金白银的延迟。

---

## 三、核心组件二：BM25 倒排索引

### 3.1 公式与直觉

```
                    IDF(qi) · f(qi, D) · (k1 + 1)
  score(D, Q) = Σ ─────────────────────────────────
                   f(qi, D) + k1 · (1 - b + b · |D|/avgdl)

  其中 IDF(qi) = ln(1 + (N - df(qi) + 0.5) / (df(qi) + 0.5))
```

三个组件各自的直觉：

| 组件 | 作用 | 参数 |
| --- | --- | --- |
| **IDF（逆文档频率）** | 出现在越少文档里的词越有区分度。「的」「公司」IDF≈0，「pgvector」IDF 很高 | — |
| **TF 饱和** | 词频贡献是**次线性**的。出现 10 次不会比 5 次得分翻倍，防止堆砌关键词刷分 | `k1 = 1.2` |
| **长度归一** | 长文档天然包含更多词，需要惩罚。**切片长度天然不均，这一项尤其重要** | `b = 0.75` |

### 3.2 IDF 为什么用平滑版本

标准 IDF 是 `ln(N/df)`，当 `df > N/2` 时会变成**负数**——
意味着「命中一个常见词反而扣分」。

Lucene 采用的平滑版本 `ln(1 + (N - df + 0.5)/(df + 0.5))` **恒为正**，
语义更合理：**命中总比不命中好，只是贡献大小不同。**

### 3.3 中文分词：为什么是 unigram + bigram 并存

```java
// ASCII 字母数字串 → 小写后整体成词
// CJK 汉字 → 同时产出 unigram（单字）与 bigram（二元组）
"知识平台" → ["知","识","平","台","知识","识平","平台"]
```

- **只用 bigram**：查询「报销」能命中「报销流程」，
  但对单字查询（「税」）召回为零；
- **只用 unigram**：「知识平台」被拆成四个毫不相关的字，噪声极大，精确率崩塌；
- **两者并存**：用 unigram 保召回、用 bigram 保精确，
  再由 BM25 的 IDF 自动为罕见 bigram 加权、为高频单字降权。

> **这不是妥协，而是让排序算法去做它擅长的事。**

### 3.4 删除必须重建倒排表（一个隐蔽但致命的坑）

```java
public int removeByDocument(TenantId tenantId, DocumentId documentId) { ... }
```

**为什么必须重建，而不能"原地删行"？**

因为倒排表里的 `docId` 是**数组下标**。直接把 `documents` 的某几行删掉，
后面所有切片的下标都会前移，而倒排表里存的还是旧下标——
结果就是**张冠李戴**：检索「报销」命中的切片，点进去是「考勤制度」。

**这类 bug 不会抛异常，只会静默返回错误知识，是最危险的一类。**

> **工程权衡**：重建是 O(n) 的。真实的 Lucene 用
> 「墓碑标记 + 后台段合并」来摊平成本——删除时打个墓碑，
> 检索时跳过，等后台合并段时再真正回收。
> 我们为了零依赖选择了重建，生产环境请直接用 Lucene/Elasticsearch。

---

## 四、核心组件三：RRF 融合

### 4.1 为什么不能"归一化后加权求和"

常见的错误做法：

```
score = 0.5 * normalize(cosine) + 0.5 * normalize(bm25)
```

它有三个致命问题：

1. 归一化依赖当前这批结果的 min/max，**换一批查询，分数含义就变了**，
   无法跨查询比较，也无法设固定阈值；
2. `min == max` 时会**除零**；
3. 权重 `0.5/0.5` 是拍脑袋定的，换一批数据就得重调。

### 4.2 RRF：只认排名，不认分数

```
               1
  RRF(d) = Σ ──────────
            i∈路  k + rank_i(d)
```

其中 `rank` 从 1 开始，`k = 60`（原论文 SIGIR 2009 的实验值）。

### 4.3 k=60 的意义

k 的作用是**压低头部结果的垄断性**：

- k 越小，第 1 名相对第 2 名的优势越大；
- k 取 60 意味着：**第 1 名（1/61）与第 10 名（1/70）的差距只有约 15%**。

这让**多路召回的一致性**比单路的高排名更重要。Demo 里的实测对比：

```
A. 向量第 1 名、关键词第 50 名：0.0255
B. 向量第 5 名、关键词第 5 名：0.0308      ← B > A
```

**两路都排第 5，胜过一路第 1、另一路第 50。**
这正是我们想要的：**两种完全不同的算法都认为相关的，才是真相关。**

### 4.4 为什么 RetrievedChunk 要保留 `ranks`

```java
public record RetrievedChunk(Chunk chunk, double score,
                             Map<String, Integer> ranks,
                             List<String> matchedBy) {
    public boolean isHitByAll() { return matchedBy.size() > 1; }
}
```

保留**原始排名**而不只是融合后的总分，既是 RRF 的前提，
也是**检索可观测性的关键**：

当用户反馈「这条该搜到却没搜到」时，你一眼就能看出——
是**两路都没召回**（召回问题），还是**一路召回了但被融合规则压低**（融合问题）。

> **可观测性不是事后加的，是在设计数据结构时就埋进去的。**

---

## 五、核心组件四：HybridRetriever（组合模式）

### 5.1 组合模式

`HybridRetriever` 本身实现了 `Retriever` 接口，内部又持有多个 `Retriever`。
对调用方而言，**「一路检索」和「多路混合」是同一种东西**——
可以再套一层混合，也可以随时摘掉某一路。

### 5.2 并行：虚拟线程

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    List<Future<RetrieverResult>> futures = new ArrayList<>();
    for (Retriever r : delegates) {
        futures.add(executor.submit(() -> safeRetrieve(r, query)));
    }
    // ...
}
```

向量检索是 CPU/内存密集（O(n·d) 浮点运算），
BM25 是内存密集（倒排表遍历），两者瓶颈不同，串行执行等于让总延迟等于两者之和。

**虚拟线程极轻，创建百万个都不心疼，而且不需要像平台线程池那样纠结池子大小。**
总延迟从 `T_dense + T_sparse` 降到 `max(T_dense, T_sparse)`。

### 5.3 优雅降级

```java
private RetrieverResult safeRetrieve(Retriever r, SearchQuery query) {
    try {
        return new RetrieverResult(r.name(), r.retrieve(query), null);
    } catch (Exception ex) {
        return new RetrieverResult(r.name(), List.of(), ex.getMessage());
    }
}
```

如果 BM25 索引损坏导致抛异常，**向量检索的结果依然有价值**。
用户拿到的检索质量下降了，但服务没挂。

> **宁可给一个不完美的结果，也不要给一个错误页面。**
> 当然，降级必须被记录和告警，否则它会悄悄变成永久状态
> （`HybridRetriever.lastFailures()` 就是为此准备的）。

---

## 六、核心组件五：KnowledgeIndexer（双索引一致性）

`HybridRetriever` 要求同一个切片**同时存在于两套索引**里。
它们是完全独立的存储结构，天然存在「写了 A 没写 B」的风险。

如果不加约束，让调用方自己分别调 `vectorStore.upsert()` 和 `bm25Index.add()`，
一旦某处漏调其中之一，就会出现「关键词能搜到但语义搜不到」或反之。
**这种 bug 很难复现，因为它只在特定的检索路径上才暴露出来。**

所以这里提供**唯一的写入口**，并让删除也走同一套逻辑。

### 6.1 DeleteReport：把一致性检查嵌入日常操作

```java
public record DeleteReport(DocumentId documentId, int vectorRemoved,
                           int bm25Removed, int registryRemoved) {
    public boolean isConsistent() {
        return vectorRemoved == bm25Removed && bm25Removed == registryRemoved;
    }
}
```

**三个数字必须相等。** 不相等就说明索引之间早已不一致
（比如历史上某次双写失败过）。

把它作为返回值暴露出来，就能在**每次删除时顺带做一次一致性自检**——
这是把「一致性检查」零成本地嵌入日常操作的技巧。

---

## 七、运行 Demo

```bash
java -cp target/classes:<依赖> com.zero.ai.agentstudy.day15knowledgeplatform.demo.Chapter07Demo
```

真实输出（节选）：

```
[1] 精确 token 检索：错误码 KP-4001
  ── 仅向量（dense）──
    doc-prod-01#4   score=0.2649
    doc-prod-02#5   score=0.1485
    doc-policy-04#3 score=0.0471       ← 完全无关的结果也混进来了
  ── 仅关键词（sparse / BM25）──
    doc-prod-01#4   score=6.2985
    doc-prod-02#5   score=4.8584
  ── 混合（RRF 融合后 Top3）──
    doc-prod-01#4   rrf=0.0328  ranks={sparse=1, dense=1}  ★两路都命中
    doc-prod-02#5   rrf=0.0323  ranks={sparse=2, dense=2}  ★两路都命中

[4] 租户隔离
  以租户 globex 检索：SearchReport{hits=0, ...}   ← 正确

[5] 知识库作用域下推 —— 一个容易被忽视的真相
  ── 关键词（BM25）──
    （无命中）← 正确！该知识库里没有一个字与「报销」匹配
  ── 向量（dense）──
    doc-prod-01#4  score=0.0765  错误码说明：KP-4001 表示向量化失败…
    doc-prod-02#5  score=0.0000  错误码说明：KP-5001 表示检索失败…
    命中了 3 条，但它们与「报销」毫无关系！
```

### 第 5 节是本 Demo 最有价值的发现

**向量检索没有「零命中」概念。**

「最近邻」永远是存在的——哪怕库里全是无关内容，
它也一定会返回余弦相似度最高的那几条。
而 BM25 是稀疏检索，一个词都没命中就是 0 条。

> **生产环境必须由此引出一个必备配置：最小相似度阈值（minScore）。**
> 低于阈值的命中直接丢弃，否则「问什么都返回一堆不相关内容」，
> 这些噪声被塞进 Prompt 后，LLM 会一本正经地胡说八道。
> 阈值需要按业务标定，一般 0.7~0.75 是个起点。

---

## 八、避坑指南

| 坑 | 后果 | 对策 |
| --- | --- | --- |
| 先全量检索再过滤权限 | 召回率归零 + 无权数据进内存 | 过滤条件作为一等参数下推（第 10 章） |
| 归一化后加权求和做融合 | 分数不可比、除零、权重要反复调 | 用 RRF，只认排名 |
| 删除索引时原地删行 | docId 错位，返回张冠李戴的错误知识 | 重建倒排表或用 Lucene 的墓碑机制 |
| 分别写两套索引 | 不一致只在特定检索路径暴露 | 唯一写入口 `KnowledgeIndexer` |
| 忽略 minScore | 问什么都返回一堆无关内容 | 配置最小相似度阈值并按业务标定 |
| 串行执行多路检索 | 延迟 = 两路之和 | 虚拟线程并行 |
| 某一路失败就整体失败 | 单点故障放大成整体不可用 | 优雅降级 + 记录告警 |

---

## 九、面试题

**Q1：为什么要用 Hybrid Search，只用向量不行吗？**

> 向量检索有三个硬伤：
> 1. **精确 token 弱**——错误码、产品型号、方法名会被泛化掉；
> 2. **低频专有名词弱**——训练语料没见过的新词表示质量差；
> 3. **没有零命中概念**——无关内容也会返回最近邻，产生噪声。
>
> 而 BM25 恰好补上这三点，但它又不懂同义改写。
> **所以两者是互补关系，不是替代关系。**
> 事实上所有主流产品（Vertex AI Search、Azure AI Search、Glean）都用 Hybrid。

**Q2：两路结果怎么融合？为什么 RRF 比加权求和好？**

> 直接加权求和要求两路分数**量纲可比**，
> 但余弦相似度（[-1,1]）和 BM25（无上界，随语料变化）根本不可比。
> 强行归一化会让分数依赖当前批次，无法跨查询比较，且 `min==max` 时会除零。
>
> RRF 完全绕开了这个问题——**它不看分数，只看排名**：
> `score = Σ 1/(60 + rank)`。
> 代价是丢掉了分数的绝对大小信息，收益是**鲁棒、无需调参、可跨查询比较**。
> 实践中 RRF 的效果通常不逊于精心调参的加权方案，而且省事得多。

**Q3：RRF 里 k=60 是怎么来的，调大调小会怎样？**

> 来自 Cormack 等人 2009 年 SIGIR 论文的实验调优。
> k 控制"头部结果的垄断程度"：k 越小，第 1 名的优势越大；
> k 越大，排名差异被抹得越平。
>
> 取 60 意味着第 1 名与第 10 名的差距只有约 15%，
> 这让**多路一致性**比单路高排名更重要。
> 如果你的场景特别看重"某一路的权威性"，可以把 k 调小；
> 如果希望更平均地对待各路，可以调大。

**Q4：检索结果里为什么要保留各路的原始排名？**

> 两个用途：
> 1. **RRF 融合的前提**——融合算法需要排名信息；
> 2. **可观测性**——当用户反馈"搜不到"时，
>    能立刻区分是**召回问题**（两路都没召回到）
>    还是**融合问题**（召回了但被压低）。
>    没有这个信息，排障只能靠猜。

---

## 十、本章小结

- **向量与关键词必须 Hybrid**，两者的短板恰好互补。
- **过滤条件必须是一等参数下推**，而不是先查全量再过滤。
- **BM25 的三个组件**（IDF / TF 饱和 / 长度归一）各司其职，
  中文用 unigram + bigram 并存兼顾召回与精确。
- **RRF 只认排名不认分数**，k=60 让"两路都认可"胜过"一路独秀"。
- **删除索引必须重建**（或换 Lucene），否则 docId 错位会静默返回错误知识。
- **向量检索没有零命中**，minScore 是生产必备配置。
- **多路并行 + 优雅降级**，让检索既快又不容易整体挂掉。

下一章，我们在召回之上加**精排**：用更贵但更准的方法，
从几十条候选里挑出最好的几条。

---

> **第 7 章结束。** 输入「继续」，进入 **第 8 章：knowledge-reranker —— 精排与 MMR 多样性去重**。
