# 第八章 端到端串联：把 8 个零件组装成一个 RAG 系统

> 五部分模板：为什么学 / 是什么 / 怎么用 / 用在哪 / 避坑与优化

---

## 一、为什么学（Why）

前七章我们逐个造了零件：

| 章节 | 零件 | 职责 |
|------|------|------|
| 3 | `VectorMath` | 余弦相似度、向量归一化 |
| 4 | `TextSplitter` | 长文本切成 Chunk |
| 5 | `Chunk` / `VectorStore` / `InMemoryVectorStore` | 向量三件套的存与查 |
| 5 | `EmbeddingClient` / `HashEmbeddingClient` | 文本 → 向量 |
| 6 | `Retriever` | 问题 → 相关片段 |
| 7 | `PromptBuilder` / `LlmClient` / `EchoLlmClient` | 片段 + 问题 → 答案 |

**但零件散着不叫系统。** 一个真正的 RAG 服务，要有一个「总指挥」把它们按顺序串起来，
对外只暴露两个动作：

- `index(文档)`：把文档灌进知识库（离线索引流）
- `ask(问题)`：问一句，返回一段有据可依的答案（在线问答流）

这一章就是把散落的零件焊成一台**能跑通端到端的机器**——这也是 RAG 学习的「毕业设计」。

---

## 二、是什么（What）

### 2.1 RAG 的两条流（全景图）

```
【离线索引流】index(text)
  原文 ──TextSplitter──▶ 多个 Chunk文本
       ──EmbeddingClient──▶ 每块的向量
       ──组装 Chunk(id+content+vector+source)──▶ VectorStore.save

【在线问答流】ask(question)
  问题 ──Retriever──▶ 相关片段 List<SearchResult>
       ──PromptBuilder──▶ 结构化 Prompt
       ──LlmClient──▶ 最终答案
```

### 2.2 RagService —— 总指挥

`RagService` 不自己干活，它只负责**编排**：持有 6 个零件的引用，
在 `index` / `ask` 两个方法里按顺序调用它们。这就是企业里 **Service 层的典型职责**：
「组织流程，不写具体算法」。

### 2.3 铁律再强调

- **铁律①**：`index` 入库和 `ask` 查询必须用**同一个 EmbeddingClient**（同一模型），
  否则两边向量不在同一空间，相似度全是噪声。RagService 构造时注入同一实例来保证。

---

## 三、怎么用（How）

### 3.1 组装（依赖注入）

```java
EmbeddingClient embedding = new HashEmbeddingClient();
VectorStore store         = new InMemoryVectorStore();
TextSplitter splitter     = new TextSplitter(50, 10);
Retriever retriever       = new Retriever(embedding, store); // 复用同一 embedding
PromptBuilder promptBuilder = new PromptBuilder();
LlmClient llm             = new EchoLlmClient();

RagService rag = new RagService(splitter, embedding, store, retriever, promptBuilder, llm);
```

### 3.2 索引 + 提问

```java
rag.index("员工每年享有10天带薪年假。试用期为3个月，转正后年假增加。", "员工手册.pdf");
String answer = rag.ask("员工有几天年假？");
System.out.println(answer);
```

### 3.3 端到端输出（本章 Demo 实测）

```
=== 索引 ===
已切分 N 块，入库后向量库共 N 条

=== 提问：员工有几天年假？ ===
[召回] 2 条相关片段
[答案] 根据资料，员工每年享有10天带薪年假（来源:员工手册.pdf）[1]；...

=== 提问：可以带宠物上班吗？（知识库没有）===
[召回] 0 条相关片段
[答案] 未在资料中找到相关信。
```

---

## 四、用在哪（Where）

- **企业知识库问答**：上传制度/手册/FAQ → 员工自助提问
- **客服助手**：产品文档入库 → 自动回答用户咨询
- **代码/文档助手**：仓库文档入库 → 「这个接口怎么用」
- 在真实项目里，`RagService` 上面再包一层 `RagController`（Spring MVC）暴露 HTTP 接口，
  `index` 对接「文件上传」，`ask` 对接「聊天框」。

---

## 五、避坑与优化（Pitfalls & Optimization）

1. **两套 Embedding 模型混用** → 相似度失效。索引与查询必须同模型（本 Demo 靠注入同一实例保证）。
2. **索引流没做去重/更新** → 同一文档反复灌入产生重复片段。生产要按文档 id 先删后插。
3. **召回为空不兜底** → LLM 瞎编。必须让 PromptBuilder 拼「（无相关资料）」并约束模型说「未找到」。
4. **切分参数拍脑袋** → 太大塞不下、太小语义碎。按业务文档结构调 chunkSize/overlap。
5. **同步阻塞** → 大文档索引慢。生产可异步化（消息队列 + 后台索引任务）。
6. **没有引用溯源** → 用户不信任。答案里带 `[编号]` 和来源，可点回原文。

### 生产化演进清单（对应 TODO.md）
- HashEmbedding → 真语义模型（通义 / OpenAI / 本地 bge）
- InMemoryVectorStore → pgvector / Milvus（持久化 + ANN 加速）
- EchoLlmClient → 真 LLM（低温度 + 流式输出）
- 加 **Query 改写**（把口语问题规范化）、**Rerank**（召回后精排）
- 加**权限过滤**（按部门/密级过滤 metadata）

---

## 本章小结

`RagService` 用两个方法 `index` / `ask` 把八个零件焊成了一台完整的 RAG 机器：
**离线索引流**（切分→向量化→入库）和**在线问答流**（召回→拼 Prompt→生成）。
到此，Day5-RAG 的核心链路全部打通——你已经能从零讲清并手写一个企业级 RAG 系统的骨架。

### 面试速答
- **Q：一个完整 RAG 系统有哪些环节？** 切分、Embedding、向量存储、检索、Prompt 组装、LLM 生成，分离线索引与在线问答两条流。
- **Q：Service 层在 RAG 里干什么？** 编排流程、注入并串联各组件，不写具体算法，保证可替换、可测试。
- **Q：如何保证召回向量与入库向量可比？** 用同一 Embedding 模型（铁律①），Service 构造时注入同一实例。

### 练习
1. 给 `RagService.index` 加「按文档 id 先删后插」的去重逻辑。
2. 把 `EchoLlmClient` 换成一个 `MockLlmClient`，让它固定返回资料条数，验证可替换性。