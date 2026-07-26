# 第六章：Retriever 检索器 —— 召回 Top-K 的完整链路

> Day5 · 第六章学习笔记。前五章我们把「切块、Embedding、存储」都备齐了，本章把它们串成 RAG **在线问答流的核心一环：给一个问题，召回最相关的资料**。

---

## 一、为什么学（核心价值）

回顾在线问答流：**用户提问 → Embedding → 检索 → 召回 Top-K → 拼 Prompt → LLM 回答**。

前面 `VectorStore.search` 只接受「查询向量」，但用户输入的是「一句中文题」。谁负责把「问题文本」变成「查询向量」？谁负责调用向量库、做阈值过滤、返回干净的召回结果？——这就是 **Retriever（检索器）** 的职责。

**Retriever = 在线问答流的「召回总指挥」**：它把「Embedding 查询 + 向量库检索 + 阈值过滤」封装成一个方法 `retrieve(question)`，上层 Service 一行调用即可拿到相关资料。

**为什么单独学**：它是「离线索引」和「在线问答」的交汇点，也是决定「答得准不准」的关键。检索器写好了，RAG 就成功了一大半。

---

## 二、是什么（概念 + 底层原理）

### 2.1 一句话定义

> Retriever（检索器）= 输入「自然语言问题」，输出「知识库中最相关的若干文本片段」的组件。内部完成：问题向量化 → 向量库检索 → 阈值过滤 → 返回。

### 2.2 检索器在流程中的位置

```
用户问题："我一年有几天年假？"
      │
      ▼  ① Retriever.retrieve(question)
┌─────────────────────────────────────────┐
│  Retriever 内部三步                        │
│  ┌─────────────────────────────────────┐ │
│  │ 1. EmbeddingClient.embed(问题)       │ │  问题 → 查询向量
│  │ 2. VectorStore.search(向量, topK)    │ │  向量 → Top-K 命中
│  │ 3. 过滤 score < 阈值 的结果           │ │  去掉不相关的
│  └─────────────────────────────────────┘ │
└─────────────────────────────────────────┘
      │
      ▼  返回 List<SearchResult>（相关片段+得分）
交给第七章：拼进 Prompt 喂给 LLM
```

### 2.3 铁律再现：查询和入库必须同一个 Embedding 模型

第二章铁律①在这里落地：**入库时用哪个 Embedding 模型，查询时就必须用同一个**。否则问题向量和文档向量不在同一个语义空间，相似度算出来是乱的。所以 Retriever 和入库流程要共享同一个 `EmbeddingClient`。

### 2.4 为什么要引入 EmbeddingClient 接口

「把文本变成向量」有三种来源（第三章）：云端 API / 本地模型 / 离线降级。为了让上层不关心用哪种，我们抽一个 `EmbeddingClient` 接口：

```
        EmbeddingClient（接口）
        └─ float[] embed(String text)
                 ▲
   ┌─────────────┼─────────────────┐
HashEmbeddingClient  OpenAiEmbeddingClient  LocalModelClient
（离线降级/本章）      （云端API/未来）        （本地模型/未来）
```

本章用 **离线哈希降级实现**（`HashEmbeddingClient`）：不依赖网络、纯本地可跑，保证「同样文本→同样向量」，用于教学演示。**注意它没有真正语义能力**，生产必须换真模型——但由于面向接口，换实现时 Retriever 一行都不用改。

### 2.5 相似度阈值：防「一本正经地瞎编」

若库里根本没有相关内容，`search` 仍会返回「最不相关的 Top-K」。若不过滤直接喂给 LLM，模型会基于无关内容硬编答案。Retriever 因此要做**阈值过滤**：

```
只保留 score >= threshold 的结果；
若全部低于阈值 → 返回空 → 上层可回答「知识库中没有相关信息」
```

这是 RAG 可信度的关键护栏。

### 2.6 Top-K 的取舍

- K 太小：漏掉关键片段，答不全。
- K 太大：召回一堆低相关内容，稀释 Prompt、增加 token 成本、甚至误导 LLM。
- 经验：**3~5 起步**，结合 chunk 大小和问题复杂度调。

---

## 三、怎么用（实战：EmbeddingClient + HashEmbeddingClient + Retriever）

本章产出三个代码文件：
1. `embedding/EmbeddingClient.java`：文本→向量的能力接口。
2. `embedding/HashEmbeddingClient.java`：离线哈希降级实现（教学用，可跑）。
3. `retriever/Retriever.java`：召回总指挥，串起 Embedding + VectorStore + 阈值过滤。

### 3.1 检索器核心逻辑

```
retrieve(question, topK, threshold):
    queryVec = embeddingClient.embed(question)     // 铁律：与入库同一模型
    results  = vectorStore.search(queryVec, topK)  // 暴力/ANN 检索
    return results.filter(r -> r.score >= threshold) // 阈值过滤
```

三行核心，把前五章成果全部串起来。（代码见下方 `.java`，已生成到对应目录。）

### 3.2 离线哈希 Embedding 怎么保证「同文本→同向量」

`HashEmbeddingClient` 思路：把文本按字符散列到固定维度的桶里累加，再归一化。它**不懂语义**（同义词不会靠近），但**确定性强**（同样输入永远同样输出），足够演示「文本→向量→检索」全链路能跑通。真实语义能力靠第三章说的云端/本地模型。

### 3.3 Python 对照（帮助理解，不在本项目运行）

```python
def retrieve(question, top_k=3, threshold=0.5):
    query_vec = embedding_client.embed(question)     # 同一模型
    results = vector_store.search(query_vec, top_k)
    return [r for r in results if r.score >= threshold]
```

Java 与 Python 逻辑一致：向量化 → 检索 → 过滤。Spring AI 里对应 `VectorStore.similaritySearch(SearchRequest)`，内部也是这套流程，只是把阈值、topK 封装进了 `SearchRequest`。

---

## 四、用在哪（真实项目）

1. **企业知识库问答**：客服/HR/IT 问答系统的召回环节，本训练营主线。
2. **搜索引擎语义召回**：用户 query 向量化后召回相关文档，再排序。
3. **推荐系统召回层**：用「用户向量」召回候选商品，交给精排。
4. **Agent 工具/记忆检索**：Agent 从长期记忆库里召回相关历史，也是 Retriever。

共同点：**凡是「给一段输入，找出最相关的若干条」，都是检索器在干活。**

---

## 五、避坑与最佳实践

1. **查询和入库用了不同 Embedding 模型** → 向量空间不一致，召回全错（铁律①）。Retriever 必须与入库共享同一 EmbeddingClient。
2. **不做阈值过滤** → 库里没相关内容也硬返回，LLM 瞎编。必须设 threshold。
3. **阈值设太高** → 明明相关的也被过滤，召回为空，答不出。需按真实数据回测校准。
4. **topK 拍脑袋** → 太大稀释、太小漏召。3~5 起步按效果调。
5. **问题没做预处理** → 用户问题含错别字/口语/无关寒暄，直接 embed 噪声大。可做基础清洗/改写（进阶：Query Rewrite）。
6. **只返回文本丢了元数据/得分** → 上层无法溯源、无法按得分展示置信度。返回完整 SearchResult。
7. **同步阻塞调用云端 Embedding 不加超时/重试** → 网络抖动拖垮请求。生产要加超时、重试、缓存。
8. **忽视召回评估** → 不知道召回好不好。应用「问题→期望片段」测试集回测命中率（Recall@K）。

**最佳实践**：Retriever 与入库共享 EmbeddingClient；阈值 + topK 双护栏并按回测校准；返回完整 SearchResult（含得分与元数据）；进阶可加 Query 改写、混合检索（向量 + 关键词）、重排序（Rerank）。

---

## 六、面试问题与参考答案

**Q1：Retriever 内部做了哪几步？**
A：三步——① 用 EmbeddingClient 把问题文本向量化；② 调 VectorStore 按相似度检索 Top-K；③ 用相似度阈值过滤掉不相关结果。输出相关片段列表交给生成环节。

**Q2：为什么查询和入库必须用同一个 Embedding 模型？**
A：不同模型的向量空间不兼容，问题向量和文档向量不在同一空间，余弦相似度失去意义，召回结果错乱。所以 Retriever 与入库流程要共享同一 EmbeddingClient（铁律①）。

**Q3：相似度阈值有什么用？不设会怎样？**
A：过滤掉「其实不相关但被强行排进 Top-K」的结果。不设阈值时，库里没有相关内容也会返回最相似的 K 条，LLM 拿到无关上下文容易一本正经地瞎编，损害可信度。

**Q4：召回效果怎么评估和优化？**
A：用「问题→期望命中片段」的测试集算 Recall@K / 命中率；优化手段包括调 chunk 大小与 overlap、调 topK 与阈值、Query 改写、向量+关键词混合检索、加 Rerank 重排序、换更强的 Embedding 模型。

---

## 七、本章今日练习

- **练习1**：为什么要把「文本→向量」抽成 EmbeddingClient 接口，而不是在 Retriever 里直接写死？
  参考：解耦。云端/本地/离线三种来源可随意切换，换实现时 Retriever 不用改，且入库和查询能共享同一实现保证铁律①。
- **练习2**：库里没有任何与问题相关的内容，Retriever 应该返回什么？为什么？
  参考：经阈值过滤后返回空列表，让上层回答「知识库中没有相关信息」，避免 LLM 基于无关内容瞎编。
- **动手**：运行 `Retriever` 的 main，先入库几条 chunk，再用一个问题检索，不同 threshold 观察召回条数变化。

---

## 本章小结

- Retriever = 召回总指挥：问题向量化 → 向量库检索 → 阈值过滤 → 返回相关片段。
- 是「离线索引」与「在线问答」的交汇点，决定答得准不准。
- 铁律①落地：查询与入库共享同一 EmbeddingClient。
- 双护栏：topK（3~5）控数量，threshold 控相关性、防瞎编。
- 面向接口：EmbeddingClient 可从离线降级平滑换成云端/本地模型。
- 本章产出代码：`embedding/EmbeddingClient.java`、`embedding/HashEmbeddingClient.java`、`retriever/Retriever.java`。

> ✅ 第六章结束。请确认后，进入 **第七章：增强生成 —— 把召回片段拼进 Prompt，让 LLM 基于资料回答**。