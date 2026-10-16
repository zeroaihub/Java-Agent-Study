# 第六章 Memory 与 RAG：让 Agent 记住一切

> 本章配套代码模块：`wechatmemory`
> 阅读时长：约 90 分钟
> 前置章节：第三章《会话管理》、第五章《命令系统》
> 后续章节：第七章《Workflow 与 Planning Agent》

---

## 一、为什么学：一个「失忆」的 Agent 有多可怕

### 1.1 从一次尴尬的客户对话说起

想象你的企业微信 Agent 上线了，客户发来一连串消息：

```
客户：我们公司叫「星海科技」，主营业务是工业机器人。
Agent：好的，很高兴认识星海科技！
客户：那你觉得我们适合用你们哪款产品？
Agent：请问贵公司是做什么业务的呢？
```

三秒钟前刚说过的话，Agent 转头就忘了。这不是 Bug，而是 **大语言模型（LLM）天生无状态（stateless）** 的必然结果——每一次调用 Chat API，模型都是「从零开始」，它不认识你，也不记得上一句。

这就是本章要解决的第一个痛点：**短期记忆（Short-term Memory）缺失**。

### 1.2 另一个致命问题：Agent 不懂「你的」知识

再看一段对话：

```
员工：我们公司的报销标准是多少？
Agent：一般企业差旅报销标准为每天 200-500 元不等……
```

模型给出的是「训练语料里的通用答案」，而不是「你们公司真实的报销制度」。它压根不知道贵司《员工报销管理办法 V3.2》里写的是「市内交通每日上限 80 元」。

更糟的是，当模型不知道答案时，它往往会**一本正经地编造**——这就是臭名昭著的 **幻觉（Hallucination）**。

这是本章要解决的第二个痛点：**长期记忆（Long-term Memory）缺失**，即 Agent 无法引用企业私有知识。

### 1.3 记忆的两个维度

| 维度 | 短期记忆 | 长期记忆 |
| --- | --- | --- |
| 定位 | 记住「本次对话」说了什么 | 记住「企业沉淀」的所有知识 |
| 生命周期 | 会话级（几分钟~几小时） | 持久级（数月~数年） |
| 存储量 | 几十条消息 | 百万级文档切片 |
| 技术手段 | 滑动窗口 + Token 预算 | RAG（检索增强生成） |
| 类比人脑 | 工作记忆（脑中暂存） | 长期记忆（查资料回忆） |
| 本章模块 | `ConversationMemory` | `KnowledgeStore` + RAG |

一个真正可用的企业 Agent，**两种记忆缺一不可**：短期记忆让它「不断片」，长期记忆让它「有依据」。

### 1.4 学完本章你将掌握

- 如何用**滑动窗口**与**Token 预算**两种策略裁剪多轮对话，既保留上下文又不撑爆模型
- RAG 的完整四步链路：**切片 -> 向量化 -> 检索 -> 增强**
- 余弦相似度检索的数学原理与工程实现
- 如何用**端口/适配器**隔离向量库，让内存实现无缝替换为 pgvector
- 多租户 RAG 的安全隔离底线
- 12 条生产级避坑经验

---

## 二、是什么：短期记忆与长期记忆的架构全景

### 2.1 短期记忆：一段会被「裁剪」的对话历史

短期记忆的本质，是把「本次会话的多轮对话」按顺序存起来，在下次调用 LLM 时**一并塞回去**，让模型「看见」上下文。

但这里有个硬约束：**LLM 的上下文窗口是有限的**（哪怕 128K token，也架不住无限追加）。所以短期记忆的核心不是「存」，而是「**如何聪明地裁剪**」。

我们提供两种裁剪策略：

```
策略一：按条数裁剪 recent(N)
  消息列表: [m1, m2, m3, m4, m5, m6, m7, m8]
  recent(3) ->                    [m6, m7, m8]
  （只保留最近 3 条，简单直接）

策略二：按 Token 预算裁剪 recentByTokenBudget(budget)
  从最新往最旧累加，累加到超预算就停：
  [m8:120t] -> [m7:200t] -> [m6:150t] -> [m5:400t 超预算,停]
  结果保留:              [m6, m7, m8]  共 470 token
  （贴合真实的模型上下文限制）
```

### 2.2 长期记忆：RAG 的四步链路

RAG（Retrieval-Augmented Generation，检索增强生成）是让 Agent「查资料再回答」的标准范式。它分**入库**与**检索增强**两条链路：

```
======================= 入库链路（一次性）=======================

  企业文档            切片              向量化              向量库
+-----------+     +----------+     +------------+     +-----------+
| 报销办法   | --> | chunk1   | --> | [0.1,0.8..] | --> |           |
| V3.2.docx |     | chunk2   |     | [0.3,0.2..] |     |  存储所有  |
|  (3000字) |     | chunk3   |     | [0.7,0.1..] |     |  切片向量  |
+-----------+     +----------+     +------------+     +-----------+
                  定长300字         文本->256维           按租户隔离
                  重叠50字          浮点向量

======================= 检索增强链路（每次提问）=================

  用户问题           向量化            相似检索           增强提示词
+-----------+     +----------+     +------------+     +-----------+
| 市内交通   | --> |[0.1,0.7..]| --> | 余弦相似度  | --> | 基于以下   |
| 报销上限   |     |          |     | topK=3     |     | 资料回答:  |
| 多少?     |     |          |     | 过滤低分    |     | [资料1..]  |
+-----------+     +----------+     +------------+     +-----------+
                                    命中chunk2         +原始问题
                                    (0.82分)           ->送LLM
```

### 2.3 关键概念速查

| 概念 | 一句话解释 |
| --- | --- |
| 切片 chunking | 把长文档切成定长小段，避免单段过长且便于精准检索 |
| 重叠 overlap | 相邻切片首尾重叠若干字，避免语义在边界被割裂 |
| 向量化 embedding | 把文本转成固定维度浮点向量，语义相近则向量相近 |
| 余弦相似度 | 两向量夹角余弦，范围约 [-1,1]，越接近 1 越相似 |
| L2 归一化 | 把向量缩放到单位长度，此时点积即余弦相似度 |
| topK | 检索时只取相似度最高的前 K 个切片 |
| 相似度阈值 | 低于阈值的召回视为噪声丢弃（如 MIN_SCORE=0.1） |
| 多租户隔离 | 以 tenantId 组织数据，一个租户绝不命中另一租户知识 |

### 2.4 wechatmemory 模块分层架构

```
wechatmemory/
+-- domain/                          领域层（零框架依赖）
|   +-- model/
|   |   +-- MessageRole              消息角色枚举 SYSTEM/USER/ASSISTANT
|   |   +-- MemoryMessage            单条记忆消息（值对象）
|   |   +-- ConversationMemory       短期记忆聚合根（含窗口裁剪）
|   |   +-- KnowledgeDocument        知识文档
|   |   +-- KnowledgeChunk           知识切片（含向量）
|   |   +-- RetrievalResult          检索结果（切片+相似度分）
|   +-- port/
|       +-- ConversationMemoryStore  短期记忆存储端口
|       +-- EmbeddingProvider        向量化端口
|       +-- KnowledgeStore           向量库端口
+-- infrastructure/                  基础设施层（适配器）
|   +-- HashingEmbeddingProvider     词哈希伪向量化（教学占位）
|   +-- InMemoryConversationMemoryStore  内存短期记忆
|   +-- InMemoryKnowledgeStore       内存向量库（余弦暴力检索）
+-- application/                     应用层（编排）
    +-- MemoryService                短期记忆闭环
    +-- KnowledgeService             RAG 双链路
```

这套分层的精髓在于：**领域层定义端口，基础设施层实现适配器**。今天用内存实现跑通逻辑，明天把 `InMemoryKnowledgeStore` 换成 `PgVectorKnowledgeStore`，应用层一行都不用改。

---

## 三、怎么用：六段核心代码逐段解析

### 3.1 MemoryMessage：一条会「自报 Token」的记忆消息

短期记忆的最小单元是一条消息。它是一个不可变值对象（record），除了角色与内容，还有一个关键方法 `estimatedTokens()`：

```java
public record MemoryMessage(MessageRole role, String content, Instant createdAt) {

    // 紧凑构造器：非空校验 + strip
    public MemoryMessage {
        Objects.requireNonNull(role, "role 不能为空");
        content = Objects.requireNonNull(content, "content 不能为空").strip();
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public static MemoryMessage user(String content)      { return new MemoryMessage(MessageRole.USER, content, Instant.now()); }
    public static MemoryMessage assistant(String content) { return new MemoryMessage(MessageRole.ASSISTANT, content, Instant.now()); }

    /** 估算 token 数：经验值「1 token ≈ 4 字符」。 */
    public int estimatedTokens() {
        return Math.max(1, content.length() / 4);
    }
}
```

**逐点解析：**

- **为什么要 `estimatedTokens()`？** 因为裁剪窗口时需要知道「这条消息值多少 token」。精确计数要用 tokenizer（如 tiktoken），但那样太重；工程上「4 字符 ≈ 1 token」是英文场景的黄金经验值（中文更接近 1.5~2 字符/token，可按需调整系数）。
-**为什么用 record + 工厂方法？** record 保证不可变（记忆一旦写入不应被篡改），工厂方法 `user()`/`assistant()` 让调用处语义清晰，无需手动传 role。

### 3.2 ConversationMemory：双策略窗口裁剪

这是短期记忆的聚合根，核心是两个裁剪方法。先看**按条数裁剪**：

```java
public List<MemoryMessage> recent(int maxTurns) {
    if (maxTurns <= 0 || messages.isEmpty()) {
        return List.of();
    }
    int from = Math.max(0, messages.size() - maxTurns);
    return Collections.unmodifiableList(new ArrayList<>(messages.subList(from, messages.size())));
}
```

`from = size - maxTurns` 定位「倒数第 N 条」的起点，`Math.max(0, ...)` 防止消息不足 N 条时越界。这是最简单直接的策略。

再看更贴合真实约束的**按 Token 预算裁剪**：

```java
public List<MemoryMessage> recentByTokenBudget(int tokenBudget) {
    if (tokenBudget <= 0 || messages.isEmpty()) {
        return List.of();
    }
    List<MemoryMessage> picked = new ArrayList<>();
    int used = 0;
    for (int i = messages.size() - 1; i >= 0; i--) {   // 从最新往最旧
        MemoryMessage m = messages.get(i);
        int cost = m.estimatedTokens();
        if (used + cost > tokenBudget && !picked.isEmpty()) {  // 超预算就停
            break;
        }
        picked.add(m);
        used += cost;
    }
    Collections.reverse(picked);   // 还原为时间升序
    return Collections.unmodifiableList(picked);
}
```

**逐点解析：**

- **为什么从最新往最旧遍历？** 因为「最近的对话最重要」。当预算有限时，宁可丢掉最早的历史，也要保住最新的上下文。
- **`&& !picked.isEmpty()` 的深意：** 即使单条消息就超预算，也至少保留它一条（否则窗口空了，模型彻底断片）。这是防御性设计。
- **为什么最后 `reverse`？** 遍历时是「新->旧」倒序收集的，喂给模型必须是「旧->新」的时间顺序，所以翻转回来。

### 3.3 MemoryService：一次问答的记忆闭环

应用层把「记录 + 读取 + 重置」编排成开箱即用的服务：

```java
@Service
public class MemoryService {
    private static final int DEFAULT_WINDOW = 20;
    private final ConversationMemoryStore memoryStore;   // 只依赖端口

    public void recordUser(String memoryKey, String content) {
        memoryStore.append(memoryKey, MemoryMessage.user(content));
    }
    public void recordAssistant(String memoryKey, String content) {
        memoryStore.append(memoryKey, MemoryMessage.assistant(content));
    }
    public List<MemoryMessage> recentWindow(String memoryKey, int maxTurns) {
        int window = maxTurns > 0 ? maxTurns : DEFAULT_WINDOW;
        return memoryStore.load(memoryKey).map(m -> m.recent(window)).orElseGet(List::of);
    }
    public void reset(String memoryKey) {   // 与 /reset 命令联动
        memoryStore.clear(memoryKey);
    }
}
```

一次完整问答的记忆闭环三步走：

```
String key = memoryService.keyOf(tenant, channel, conv);
memoryService.recordUser(key, userText);                    // 1. 记录用户输入
List<MemoryMessage> history = memoryService.recentWindow(key, 20);  // 2. 取窗口喂模型
// ... 调用 LLM 得到 reply ...
memoryService.recordAssistant(key, reply);                  // 3. 记录模型回复
```

注意 `MemoryService` 只依赖 `ConversationMemoryStore` 端口，完全不知道底层是内存还是 Redis——这正是六边形架构的价值。

### 3.4 HashingEmbeddingProvider：词哈希伪向量化

向量化是 RAG 的入口。生产环境用 Spring AI 的 `EmbeddingModel` 调用真实模型（如 text-embedding-3），但为了让教程**零外部依赖即可运行**，我们用「词哈希」造一个确定性的伪向量：

```java
@Component
public class HashingEmbeddingProvider implements EmbeddingProvider {
    private static final int DIMENSION = 256;

    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIMENSION];
        String[] tokens = text.toLowerCase(Locale.ROOT).split("[^\\p{Alnum}\\p{IsHan}]+");
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            // 把每个词哈希到某一维，累加词频
            vector[Math.floorMod(token.hashCode(), DIMENSION)] += 1.0f;
        }
        normalize(vector);   // L2 归一化：缩放到单位长度
       return vector;
    }
    @Override public int dimension() { return DIMENSION; }
}
```

**逐点解析：**

- **原理：** 每个词哈希到固定维度的某一格，累加词频，得到一个「词袋向量」。含相同词越多的两段文本，向量越接近。
- **为什么要 L2 归一化？** 归一化后所有向量都在单位球面上，此时**点积 == 余弦相似度**，检索时省一次除法。
- **局限性（务必牢记）：** 词哈希只捕捉「词共现」，不懂真正的语义——「番茄」和「西红柿」在它眼里毫不相关。生产环境**必须**替换为真实 embedding 模型。这也是端口抽象存在的意义：换实现，应用层零改动。

### 3.5 InMemoryKnowledgeStore：余弦相似度暴力检索

向量库的核心是「给一个查询向量，找出最相似的 topK 个切片」。内存实现用暴力最近邻：

```java
@Override
public List<RetrievalResult> search(String tenantId, float[] queryVector, int topK) {
    List<KnowledgeChunk> chunks = store.get(tenantId);   // 多租户隔离：只查本租户
    if (chunks == null || chunks.isEmpty() || queryVector == null || topK <= 0) {
        return List.of();
    }
    List<RetrievalResult> results = new ArrayList<>(chunks.size());
    for (KnowledgeChunk chunk : List.copyOf(chunks)) {   // 快照遍历，防并发修改
        if (!chunk.hasEmbedding()) continue;
        double score = cosineSimilarity(queryVector, chunk.embedding());
        results.add(new RetrievalResult(chunk, score));
    }
    results.sort(null);   // RetrievalResult 已实现 Comparable（按 score 降序）
    return results.size() > topK ? new ArrayList<>(results.subList(0, topK)) : results;
}

private double cosineSimilarity(float[] a, float[] b) {
    double dot = 0, normA = 0, normB = 0;
    for (int i = 0; i < a.length; i++) {
        dot += (double) a[i] * b[i];
        normA += (double) a[i] * a[i];
        normB += (double) b[i] * b[i];
    }
    if (normA == 0 || normB == 0) return 0.0;
    return dot / (Math.sqrt(normA) * Math.sqrt(normB));   // 点积/模长乘积
}
```

**逐点解析：**

- **多租户隔离是安全底线：** `store.get(tenantId)` 决定了检索永远只在当前租户的切片里进行——A 公司的问题绝不会命中 B 公司的知识。企业级 RAG 若漏掉这一步，就是重大数据泄露事故。
- **余弦相似度公式：** 点积 ÷ (模长 A × 模长 B)，几何意义是两向量的夹角余弦。夹角越小（越同向）值越接近 1。
- **`results.sort(null)` 的秘密：** `RetrievalResult` 实现了 `Comparable` 且按 score **降序**排列，所以传 null 即用自然序，排完直接取前 topK。
- **与 pgvector 的关系：** 这段暴力检索在语义上等价于 pgvector 的 `ORDER BY embedding <=> query LIMIT k`，唯一区别是 pgvector 有 HNSW 索引做近似加速。百万级数据下暴力检索会慢，这就是为什么生产要上专业向量库。

### 3.6 KnowledgeService.augment：RAG 的临门一脚

前面所有铺垫，都是为了这最后一步——把检索到的知识拼进提示词，让模型「照着资料回答」：

```java
public String augment(String tenantId, String question, int topK) {
    List<RetrievalResult> hits = retrieve(tenantId, question, topK);
    if (hits.isEmpty()) {
        return question;   // 无相关知识，原样返回（不强行编造上下文）
    }
    StringBuilder prompt = new StringBuilder();
    prompt.append("请依据以下企业知识库资料回答用户问题；若资料不足以回答，请如实说明，不要编造。\n\n");
    prompt.append("=== 知识库资料 ===\n");
    int idx = 1;
    for (RetrievalResult hit : hits) {
        prompt.append("[资料").append(idx++).append("] ").append(hit.content()).append("\n");
    }
    prompt.append("\n=== 用户问题 ===\n").append(question);
    return prompt.toString();
}
```

**逐点解析：**

- **降幻觉的本质：** 提示词里那句「若资料不足以回答，请如实说明，不要编造」是灵魂。它把模型从「凭记忆瞎答」拉回到「基于给定资料回答」，这是 RAG 抑制幻觉的核心机制。
- **可溯源：** 每条资料都编号 `[资料1] [资料2]`，模型回答时可以引用来源，便于用户核实。
- **优雅降级：** 检索为空时原样返回问题，而不是硬塞一个空的「知识库资料」区块误导模型。
- **切片入库链路（`ingest`）** 与此对称：文档 -> `splitIntoChunks`（定长 300、重叠 50）-> 逐片 `embed` -> `saveAll`。切片的重叠设计，正是为了防止「市内交通每日上限」这句话恰好被切在两片边界而检索不到。

---

## 四、真实项目：两条完整旅程推演

### 4.1 旅程一：多轮对话的记忆闭环

回到本章开头那段尴尬对话。有了短期记忆后，实际发生了什么？

```
------------------ 第 1 轮 ------------------
客户: 我们公司叫「星海科技」，主营业务是工业机器人。

  key = memoryService.keyOf("t_001", "WECHAT", "c_888")
      = "t_001:WECHAT:c_888"
  recordUser(key, "我们公司叫星海科技...")     -> messages=[u1]
  recentWindow(key, 20) -> [u1]                -> 拼进提示词送 LLM
  reply = "好的，很高兴认识星海科技！"
  recordAssistant(key, reply)                  -> messages=[u1, a1]

------------------ 第 2 轮 ------------------
客户: 那你觉得我们适合用你们哪款产品？

  recordUser(key, "那你觉得我们适合...")        -> messages=[u1,a1,u2]
  recentWindow(key, 20) -> [u1, a1, u2]        -> 模型看得见「星海科技/工业机器人」!
  reply = "针对工业机器人企业，推荐我们的 X 型协作臂方案..."
  recordAssistant(key, reply)                  -> messages=[u1,a1,u2,a2]
```

**关键差异：** 第 2 轮调用 `recentWindow` 时，历史里已包含第 1 轮的「星海科技/工业机器人」。模型据此给出了**上下文连贯**的推荐，而不是再问一遍「贵公司做什么」。记忆闭环达成。

### 4.2 旅程二：企业 FAQ 知识库问答

场景：员工问「市内交通报销上限是多少」，知识库里存了《报销办法 V3.2》。

**第一阶段——文档入库（管理员一次性操作）：**

```
KnowledgeDocument doc = KnowledgeDocument.create(
    "t_001", "员工报销管理办法V3.2",
    "……第三条 市内交通：员工市内公务出行，每日报销上限 80 元，需提供正规发票……（共3000字）",
    "hr/报销办法.docx");

knowledgeService.ingest(doc);
  -> splitIntoChunks(3000字) -> 11 个切片（每片300字，重叠50）
  -> 逐片 embed() -> 11 个 256 维向量
  -> knowledgeStore.saveAll() -> 存入 store["t_001"]
  日志: 文档入库完成: tenant=t_001, doc=..., chunks=11
```

**第二阶段——员工提问（每次问答）：**

```
员工: 市内交通报销上限是多少？

  augment("t_001", "市内交通报销上限是多少?", 3)
    |
    +-> retrieve("t_001", question, 3)
    |     +-> embed("市内交通报销上限...") -> 查询向量
    |     +-> knowledgeStore.search("t_001", 查询向量, 3)
    |     |     余弦相似度打分:
    |     |       chunk3 "市内交通每日上限80元..."  -> 0.82  <- 命中!
    |     |       chunk7 "差旅住宿标准..."          -> 0.15
    |     |       chunk1 "总则适用范围..."          -> 0.06
    |     |     sort(null) 降序 -> [chunk3, chunk7, chunk1]
    |     +-> 过滤 MIN_SCORE=0.1 -> [chunk3(0.82), chunk7(0.15)]  (chunk1被丢弃)
    |
    +-> 拼装增强提示词:
          请依据以下企业知识库资料回答用户问题；若资料不足...
          === 知识库资料 ===
          [资料1] 市内交通每日上限80元，需提供正规发票...
          [资料2] 差旅住宿标准...
          === 用户问题 ===
          市内交通报销上限是多少?
    |
    +-> 送 LLM -> "根据《报销办法 V3.2》，市内交通每日报销上限为 80 元，需提供正规发票。"
```

**关键差异：** 模型不再瞎编「200-500 元」，而是**基于企业真实制度**回答「80 元」，且可溯源到《报销办法 V3.2》。这就是 RAG 的价值。

### 4.3 两条旅程小结

| 维度 | 旅程一（短期记忆） | 旅程二（长期记忆 RAG） |
| --- | --- | --- |
| 触发时机 | 每轮对话自动记录 | 提问时实时检索 |
| 数据来源 | 本次会话历史 | 企业知识库文档 |
| 核心动作 | `recentWindow` 取窗口 | `augment` 拼装知识 |
| 解决的痛点 | 对话「断片」 | 幻觉 + 不懂企业知识 |
| 存储载体 | `ConversationMemory` | `KnowledgeStore` |
| 生命周期 | 会话结束/`reset` 即清 | 长期沉淀 |

两者协同：员工问「上次说的报销事，那出差住宿呢？」——短期记忆让 Agent 记得「上次说的报销事」，长期记忆让它答出准确的「住宿标准」。**这才是完整的企业级 Agent 记忆能力。**

---

## 五、避坑指南：12 条生产血泪经验

**1. 窗口过大撑爆上下文。** 无脑把全部历史塞给模型，token 一旦超上限直接报错或被截断。务必用 `recent(N)` 或 `recentByTokenBudget` 裁剪。经验：普通客服场景 20 轮足矣。

**2. Token 估算过于粗糙导致溢出。** 「4 字符/token」对英文准，对中文偏差大（中文约 1.5~2 字符/token）。若你的场景以中文为主，务必调小系数（如 `length()/2`），并留 20% 安全余量给模型输出。

**3. 切片过大或过小都出问题。** 切片太大（如 2000 字）会稀释语义、召回不精准；太小（如 50 字）会割裂上下文。经验：中文 300~500 字、英文 500~800 字符较均衡。

**4. 忘记切片重叠导致边界信息丢失。** 若「每日上限 80 元」这句恰好被切在两片交界，且无重叠，则任一片都不完整，检索必然失败。`CHUNK_OVERLAP` 不可省。

**5. 相似度阈值缺失召回噪声。** 不设 `MIN_SCORE`，则哪怕毫不相关的切片也会被当 topK 返回，污染提示词、诱发幻觉。务必过滤低分召回。

**6. 多租户隔离缺失=数据泄露。** 检索时若不以 `tenantId` 圈定范围，A 公司可能检索到 B 公司的机密。这是企业级 RAG 的**红线**，绝不能靠「查询条件里加个 where」侥幸，要在存储结构上物理隔离。

**7. 向量维度不一致检索失效。** 入库用 256 维、检索用 512 维，余弦相似度直接返回 0（本实现有 `a.length != b.length` 保护）。切换 embedding 模型后，**必须清空重建全部向量**，绝不能新旧混存。

**8. 伪向量化直接上生产。** `HashingEmbeddingProvider` 只懂词共现、不懂语义，「番茄≠西红柿」。它仅供教学/离线跑通，生产**必须**换真实 embedding 模型。

**9. 记忆与会话生命周期不对齐。** 会话已过期清理，但短期记忆还留着，导致「僵尸记忆」占内存、甚至串到新会话。`/reset` 命令应同时触发 `SessionService.reset` 与 `MemoryService.reset`。

**10. 并发 append 丢消息。** 多线程同时追加同一会话记忆，若用普通 `HashMap` + `get-改-put`，会丢更新。本实现的 `InMemoryConversationMemoryStore` 用 `ConcurrentHashMap.compute` 保证读改写原子性。

**11. 检索结果不做溯源。** 只把知识内容拼进提示词、不保留文档来源，用户质疑答案时无从核实。生产应在 `RetrievalResult` 中带上 `documentId`/来源，回答时标注引用。

**12. RAG 万能论。** RAG 不解决「知识库里根本没有的信息」，也不适合「需要精确计算/实时数据」的场景。它只是「检索 + 拼提示词」，不是魔法。知识库内容的质量，直接决定 RAG 的天花板——垃圾进，垃圾出。

---

## 六、面试题精选

### Q1：短期记忆和长期记忆有什么区别？分别怎么实现？

**参考答案：** 短期记忆记住「本次会话」的多轮对话，生命周期是会话级（几分钟到几小时），存储量小（几十条），用**滑动窗口/Token 预算裁剪**实现，载体是 `ConversationMemory`。长期记忆记住「企业沉淀」的知识，生命周期持久（数月数年），存储量大（百万级切片），用 **RAG** 实现，载体是向量库。两者协同：短期让 Agent「不断片」，长期让它「有依据不幻觉」。实现上短期记忆可用 Redis（带 TTL），长期记忆用 pgvector/Milvus 等向量库。

### Q2：请完整描述 RAG 的工作流程，它为什么能降低幻觉？

**参考答案：** RAG 分两条链路。**入库链路（一次性）**：文档 -> 切片（定长+重叠）-> 向量化（embedding 模型）-> 存入向量库。**检索增强链路（每次提问）**：问题 -> 向量化 -> 在向量库按相似度检索 topK -> 过滤低分 -> 把命中知识拼进提示词 -> 送 LLM 生成答案。**降幻觉原理**：它把模型从「凭训练记忆瞎答」变成「基于给定的真实资料回答」，并在提示词中明确要求「资料不足时如实说明、不要编造」，从而将答案锚定在可控、可溯源的知识上。

### Q3：什么是余弦相似度？为什么向量检索常用它而非欧氏距离？

**参考答案：** 余弦相似度 = 两向量点积 ÷ 模长乘积，几何意义是夹角余弦，取值约 [-1,1]，越接近 1 越相似。它只看**方向**不看长度，对文本 embedding 尤其合适——因为文本向量的「方向」代表语义、「长度」常受文本长短影响，用余弦能消除长度干扰。若向量已 L2 归一化，点积本身即余弦相似度，计算更快。欧氏距离受向量绝对大小影响大，在高维稀疏语义空间中区分度不如余弦。pgvector 的 `<=>` 算子即余弦距离。

### Q4：向量库如何选型？pgvector 和 Milvus 各适合什么场景？

**参考答案：** **pgvector** 是 PostgreSQL 扩展，优势是「与现有关系库同库」——事务、备份、权限、SQL join 全复用，运维简单，适合数据量中等（百万级以内）、已用 PG 的团队，本项目即以它为生产目标。**Milvus** 是专业向量数据库，支持 HNSW/IVF 等多种 ANN 索引、水平扩展、十亿级向量，适合超大规模、高 QPS 的独立向量检索场景。选型原则：数据量不大、想少维护一套系统 -> pgvector；向量规模巨大、检索性能要求极致 -> Milvus。无论选哪个，只要实现本章的 `KnowledgeStore` 端口，应用层都无需改动。

---

## 七、本章小结

| 知识点 | 核心要点 | 对应代码 |
| --- | --- | --- |
| 短期记忆本质 | LLM 无状态，需把历史回塞 | `ConversationMemory` |
| 窗口裁剪 | 按条数 / 按 Token 预算两策略 | `recent` / `recentByTokenBudget` |
| Token 估算 | 「1 token ≈ 4 字符」经验值 | `MemoryMessage.estimatedTokens` |
| 记忆闭环 | 记录用户 -> 取窗口 -> 记录回复 | `MemoryService` |
| RAG 入库 | 切片 -> 向量化 -> 存储 | `KnowledgeService.ingest` |
| 切片策略 | 定长 300 + 重叠 50 防边界割裂 | `splitIntoChunks` |
| 向量化 | 文本 -> 定维浮点向量 + L2 归一化 | `HashingEmbeddingProvider` |
| 相似检索 | 余弦相似度暴力最近邻 + topK | `InMemoryKnowledgeStore.search` |
| 检索增强 | 拼「基于资料回答」提示词降幻觉 | `KnowledgeService.augment` |
| 多租户隔离 | tenantId 组织数据，物理隔离 | `store.get(tenantId)` |
| 端口/适配器 | 内存实现可无缝换 Redis/pgvector | `*Store` / `EmbeddingProvider` |

**一句话总结本章：** 短期记忆用「滑动窗口」让 Agent 记住本次对话，长期记忆用「RAG」让 Agent 引用企业知识、告别幻觉——两者协同，Agent 才真正「记住一切」。

---

## 八、扩展阅读

- **Spring AI 官方文档**：`EmbeddingModel`、`VectorStore`、`ETL Pipeline`（DocumentReader / TextSplitter / EmbeddingModel 三件套），本章的手写实现在生产中都可用 Spring AI 现成组件替代。
- **pgvector 官方仓库**：`CREATE EXTENSION vector`、`vector` 列类型、`<=>`（余弦）/`<->`（L2）/`<#>`（内积）三种距离算子，以及 HNSW/IVFFlat 索引创建。
- **《Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks》**（Lewis et al., 2020）：RAG 的开山论文。
- **切片进阶**：递归字符切分（RecursiveCharacterTextSplitter）、按语义/句子边界切分、父子切片（small-to-big）等高级策略。
- **重排序（Rerank）**：在向量召回后加一层 cross-encoder 重排，进一步提升 topK 精度，是生产 RAG 的常见增强环节。
- **本项目相关模块**：第三章 `wechatsession`（会话生命周期，与短期记忆对齐）、第五章 `wechatcommand`（`/reset` 命令联动记忆重置）。

---

## 九、下一章预告

短期记忆让 Agent 记住对话，长期记忆让它博学多识。但目前的 Agent 还只会「一问一答」——面对「帮我整理今天所有会议内容，生成纪要并发给项目组」这种**多步骤复杂任务**，它无从下手。

**第七章《Workflow 与 Planning Agent》** 将带你构建 Agent 的「大脑」：
- **Planning Agent**：如何让 Agent 把一个大任务**自动分解**成有序的子任务
- **Workflow 编排**：如何用状态机/DAG 驱动多步骤流程，支持顺序、并行、条件分支
- **步骤重试与补偿**：单步失败如何优雅重试、回滚
- **Human-in-the-Loop**：关键步骤如何插入人工审批（Human Approval）
- 配套模块 `wechatworkflow` 全量落地

从「会记忆」到「会规划」，Agent 正在从「聊天机器人」进化为「能干活的数字员工」。我们下章见。