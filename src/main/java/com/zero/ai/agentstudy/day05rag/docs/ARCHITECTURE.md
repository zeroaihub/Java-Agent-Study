# Day5 架构说明：RAG（检索增强生成）

> 本文档描述 Day5「RAG / 检索增强生成」落地代码的整体架构、分层职责、数据流与扩展方向。
> 所有代码位于独立包 `com.zero.ai.agentstudy.day05rag`，基于 Java 21 + Spring Boot 4.1.0 + Spring AI 2.0 GA，可独立运行。

---

## 一、分层架构总览

```
HTTP 请求
   │
   ▼
Controller 层  (mycode/controller/RagController)
   │  暴露 /api/day05/rag/ingest（写入）和 /api/day05/rag/ask（问答）
   ▼
Service 编排层  (mycode/service/RagService)
   │  编排「离线索引流」与「在线问答流」
   ▼
┌─────────────────────────────────────────────────────┐
│  离线索引流（Ingest）                                │
│  TextSplitter → EmbeddingClient → VectorStore       │
│  文档切分       文本→向量         向量存储            │
├─────────────────────────────────────────────────────┤
│  在线问答流（Ask）                                   │
│  EmbeddingClient → VectorStore → PromptBuilder → LLM│
│  问题→向量       相似检索Top-K   组装Prompt   生成答案│
└─────────────────────────────────────────────────────┘
```

核心设计原则：**每一层只做一件事**。替换任何一层（如内存向量库 → pgvector），其他层完全不动。

---

## 二、各层职责

| 层 | 类 | 职责 |
|----|----|----|
| Controller | `RagController` | 暴露 ingest / ask 两个 HTTP 入口，参数校验 |
| Service | `RagService` | 编排索引流（切分→Embedding→存储）和问答流（Embedding→检索→组装→生成） |
| Splitter | `TextSplitter` | 将长文本按固定大小 + 重叠切成 Chunk 列表 |
| Embedding | `EmbeddingClient` / `HashEmbeddingClient` | 文本 → 固定维度向量（接口 + 本地哈希降级实现） |
| VectorStore | `InMemoryVectorStore` | 向量存储 + 余弦相似度 Top-K 检索（内存版） |
| Retriever | `DocumentRetriever` | 封装检索逻辑，返回 Top-K 相关 Chunk |
| Util | `SimilarityUtil` | 余弦相似度计算工具 |
| Entity | `Chunk` / `EmbeddedChunk` | 领域对象：文本块 / 带向量的文本块 |
| DTO | `IngestRequest/Response`、`AskRequest/Response` | 请求/响应数据传输对象 |

---

## 三、两条数据流

### 3.1 离线索引流（写入知识库）

```
POST /api/day05/rag/ingest
  │ Body: {"title":"请假制度","content":"员工年假为10天……"}
  ▼
RagService.ingest(title, content)
  ├─ 1. TextSplitter.split(content)     → List<Chunk>
  ├─ 2. EmbeddingClient.embed(chunk)    → float[] 向量
  ├─ 3. 组装 EmbeddedChunk（原文 + 向量 + 元数据）
  └─ 4. VectorStore.add(embeddedChunk)  → 存入向量库
```

### 3.2 在线问答流（用户提问）

```
POST /api/day05/rag/ask
  │ Body: {"question":"我一年有几天年假？"}
  ▼
RagService.ask(question)
  ├─ 1. EmbeddingClient.embed(question)  → 问题向量
  ├─ 2. VectorStore.search(vector, topK) → Top-K 相关 Chunk
  ├─ 3. PromptBuilder.build(question, chunks) → 组装 Prompt
  ├─ 4. LlmClient.generate(prompt)       → LLM 基于资料回答
  └─ 返回答案 + 引用出处
```

---

## 四、接口清单

| 接口 | 方法 | 作用 |
|------|------|------|
| `/api/day05/rag/ingest` | POST | 写入知识（切分→Embedding→存储） |
| `/api/day05/rag/ask` | POST | 智能问答（检索→组装→生成） |

---

## 五、核心设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| Embedding | HashEmbeddingClient（本地哈希） | 零外部依赖，Demo 可离线跑通；生产替换为真实模型 |
| VectorStore | InMemoryVectorStore | 零依赖启动；生产替换为 pgvector / Milvus |
| LLM | EchoLlmClient（回显降级） | 无模型时也能验证检索链路；生产替换为 Spring AI ChatClient |
| 切分策略 | 固定大小 + 重叠 | 简单可控；进阶可替换为语义切分 |
| 相似度 | 余弦相似度 | 对文本向量最通用，不受向量长度影响 |

---

## 六、目录结构

```
day05rag/
├── docs/                     # 学习资料
│   ├── README.md             # 整天概览导读
│   ├── ARCHITECTURE.md       # 本文档
│   ├── TODO.md               # 动手任务清单
│   └── chapters/             # 各章学习笔记
├── embedding/                # 教学版：Embedding 接口 + 哈希实现
├── entity/                   # 教学版：Chunk / SearchResult
├── llm/                      # 教学版：LlmClient 接口 + Echo 降级
├── prompt/                   # 教学版：PromptBuilder
├── retriever/                # 教学版：Retriever
├── service/                  # 教学版：RagService
├── splitter/                 # 教学版：TextSplitter
├── util/                     # 教学版：VectorMath
├── vectorstore/              # 教学版：VectorStore 接口 + 内存实现
└── mycode/                   # 完整企业分层实现（学生版）
    ├── controller/           # RagController
    ├── dto/                  # 请求/响应 DTO
    ├── embedding/            # EmbeddingClient + HashEmbeddingClient
    ├── entity/               # Chunk / EmbeddedChunk
    ├── retriever/            # DocumentRetriever
    ├── service/              # RagService
    ├── splitter/             # TextSplitter
    ├── util/                 # SimilarityUtil
    └── vectorstore/          # InMemoryVectorStore
```

---

## 七、扩展方向（Demo → 生产级）

- **真实 Embedding**：替换 HashEmbeddingClient 为 OpenAI / 本地 BGE 模型（维度 768~1536）。
- **真实向量库**：替换 InMemoryVectorStore 为 pgvector / Milvus / Qdrant，支持持久化和百万级检索。
- **语义切分**：替换固定大小切分为基于段落/标题/语义的切分策略。
- **Rerank 精排**：对 Top-K 召回结果做二次排序（Cross-Encoder / Cohere Rerank）。
- **Hybrid Search**：关键词检索（BM25）+ 向量检索融合，提升召回率。
- **多知识库 / 多租户**：按租户/业务域隔离向量集合。
- **引用出处**：回答中附带 Chunk 来源（文档标题 + 段落位置）。
- **缓存优化**：高频问题 Embedding 缓存，减少重复计算。
- **可观测**：检索命中率、召回延迟、LLM Token 消耗指标。
- **接入 Agent**：RAG 作为 Tool 被 Agent 调用（Day6 Workflow 衔接）。
