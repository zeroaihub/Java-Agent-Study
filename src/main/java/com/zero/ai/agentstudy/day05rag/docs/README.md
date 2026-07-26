# Day5：RAG（Retrieval-Augmented Generation）检索增强生成

> 《Java 程序员转 AI Agent 架构师（120 天）》训练营 · Day5 学习资料
>
> 本文档为「自包含学习资料」：即使你以后完全不看聊天记录，只打开这份 README，也能重新学习 Day5 的全部内容。
>
> 阅读对象：有 Java / Spring Boot 基础、想成为企业级 AI Agent 架构师的工程师。

---

## 〇、写在最前面：Day5 在整个训练营中的位置

前四天我们完成了：

- **Day1 LLM**：搞清楚大模型是什么、Token、上下文窗口、生成原理。
- **Day2 API**：用 Java 直接调用 Chat Completions，理解 message 数组、system prompt、流式、结构化输出。
- **Day3 Tool / Function Calling**：让模型「会用工具」，把外部能力接进来。
- **Day4 Memory**：让 Agent「有记忆」，能记住对话历史与用户画像。

到 Day4 为止，我们的 Agent 已经「会说话、会用工具、有记忆」。但它有一个致命短板：

> **它只知道训练时见过的公开知识，完全不知道你公司内部的知识。**

Day5 的 RAG，就是解决这个短板的核心技术。它是所有「企业级 Agent」的地基。**没有 RAG，就没有真正落地的企业 Agent。**

---

## 一、今天的学习目标

学完今天，你必须能够真正回答 / 做到以下几件事：

1. 说清楚 **为什么企业 Agent 必须使用 RAG**，而不是把知识写进 Prompt 或去微调模型。
2. 说清楚 **RAG 到底解决了什么问题**（知识时效性、私有知识、幻觉、成本）。
3. 画出并讲解一个 **企业级 RAG 的完整架构与数据流**。
4. 理解 RAG 的六个底层概念：**Embedding、Chunk、Vector、Similarity Search、Retrieval、Augmented Generation**。
5. 用 **Java + Spring Boot + Spring AI** 实现一个完整、可运行的 RAG 系统。
6. 完成一个 **企业级 RAG Demo**：上传文档 → 切分 → Embedding → 存储 → 提问 → 召回 → 生成回答。
7. 掌握至少 **10 个企业 RAG 常见坑** 以及对应的优化手段（Rerank、Hybrid Search、多知识库、租户、成本优化）。

---

## 二、今天的知识体系（全景图）

```
RAG（检索增强生成）
├── 1. 为什么需要 RAG（价值 / 痛点）
│     ├── LLM 的知识边界
│     ├── Prompt 塞不下 / 成本高 / 会截断
│     └── 微调（Fine-tune）为什么不划算
│
├── 2. RAG 底层原理（六大概念）
│     ├── Embedding      文本 → 向量
│     ├── Chunk          文档 → 小块
│     ├── Vector Store   向量 → 存储 / 索引
│     ├── Similarity     向量 → 相似度（余弦 / 内积 / L2）
│     ├── Retrieval      问题 → 召回 Top-K 相关块
│     └── Augmented Gen  相关块 + 问题 → 提示词 → LLM 回答
│
├── 3. Embedding 深入（为什么一句话能变成向量）
├── 4. Chunk 切分策略（大小 / 重叠 / 语义切分）
├── 5. 向量数据库选型（pgvector / Milvus / Qdrant / Chroma / Pinecone）
├── 6. Spring AI 实现 RAG（企业分层写法）
├── 7. 企业级 RAG Demo（端到端）
└── 8. 企业最佳实践（Rerank / Hybrid / 多租户 / 成本 / 召回优化）
```

---

## 三、章节目录

| 章节 | 主题 | 你将学到 |
| --- | --- | --- |
| 第一章 | 为什么需要 RAG | LLM 知识边界、Prompt / 微调的局限、RAG 解决的问题 |
| 第二章 | RAG 底层原理 | 六大概念 + 完整流程图（离线索引 + 在线问答） |
| 第三章 | Embedding | 向量的本质、相似度、为什么相近问题距离更近 |
| 第四章 | Chunk 切分 | 为什么切、怎么切、大小与重叠如何选 |
| 第五章 | 向量数据库 | 五大向量库对比、企业如何选型、为什么本项目用 pgvector |
| 第六章 | Spring AI 实现 RAG | 企业分层：Controller/Service/Retriever/VectorStore/Embedding |
| 第七章 | 企业级 RAG Demo | 端到端可运行：上传→切分→Embedding→召回→回答 |
| 第八章 | 企业最佳实践 | 10 大坑、Rerank、Hybrid Search、多租户、成本优化 |

---

## 四、核心知识点介绍（先建立整体认知）

### 4.1 什么是 RAG（一句话定义）

> RAG = 先「检索（Retrieval）」出与问题相关的资料，再把资料塞进提示词让大模型「生成（Generation）」答案。

它把「大模型」和「你的私有知识库」用「向量检索」连接起来。模型不再凭记忆瞎编，而是**基于你给它的真实资料回答**。

### 4.2 RAG 的两条主线

RAG 系统永远由两条数据流组成，理解这两条线，你就理解了 RAG 的一大半：

**① 离线索引流（Indexing，写入知识库时发生一次）**

```
原始文档(PDF/Word/网页)
   → 解析(Parse) 提取纯文本
   → 切分(Chunk) 切成一段段小文本
   → 向量化(Embedding) 每段文本 → 一个向量
   → 存储(Vector Store) 向量 + 原文 存进向量库
```

**② 在线问答流（Query，用户每次提问时发生）**

```
用户问题
   → 向量化(Embedding) 问题 → 一个向量
   → 相似检索(Similarity Search) 在向量库找最相近的 Top-K 段
   → 拼装提示词(Augment) 把 Top-K 原文 + 问题拼成 Prompt
   → 大模型生成(Generation) LLM 基于资料回答
   → 返回答案(可附带引用出处)
```

### 4.3 六大底层概念速览

- **Embedding（嵌入）**：把一段文本映射成一个固定长度的浮点数组（如 1536 维）。语义相近的文本，向量在空间中距离也近。
- **Chunk（切块）**：长文档不能整篇 Embedding，要切成小块（如 300~800 字），每块单独向量化。
- **Vector（向量）**：就是一个数字数组，代表文本在「语义空」中的坐标。
- **Similarity Search（相似检索）**：用余弦相似度 / 内积 / 欧氏距离，找出与问题向量最接近的若干个块。
- **Retrieval（召回）**：相似检索的结果，即「找回来的相关资料」。
- **Augmented Generation（增强生成）**：把召回的资料拼进 Prompt，让模型基于资料生成答案。

### 4.4 为什么不用别的方案？（关键结论，第一章展开）

| 方案 | 问题 |
| --- | --- |
| 把知识写进 Prompt | 上下文窗口塞不下、每次都贵、会被截断、无法更新 |
| Fine-tune 微调模型 | 成本高、周期长、知识更新要重训、容易遗忘、无法追溯出处 |
| **RAG** | **知识随时更新、成本低、可追溯出处、私有可控** ✅ |

---

## 五、学完今天你将掌握的能力

- 能独立设计一个企业级 RAG 架构，并向团队讲清楚每一层为什么存在。
- 能用 Spring AI 从零搭出「上传文档 → 提问」的完整闭环。
- 能诊断「RAG 回答不准」的常见原因（切分、召回、Embedding、Prompt）。
- 能做召回优化：调 Chunk、加 Rerank、上 Hybrid Search。
- 能做成本优化：缓存 Embedding、控制 Top-K、压缩上下文。
- 能应对面试：RAG 原理、向量库选型、幻觉治理、多租户隔离。

---

## 六、今日学习路线图

```
第一章 为什么需要 RAG      —— 建立动机（先想通，再动手）
   ↓
第二章 底层原理 + 流程图    —— 建立全局心智模型
   ↓
第三章 Embedding          —— 理解「文本变向量」
   ↓
第四章 Chunk 切分         —— 理解「文档怎么切」
   ↓
第五章 向量数据库          —— 理解「向量存哪、怎么查」
   ↓
第六章 Spring AI 实现      —— 开始写企业分层代码
   ↓
第七章 端到端 Demo        —— 跑通完整闭环
   ↓
第八章 企业最佳实践        —— 从「能跑」到「能上线」
```

---

## 七、今日项目说明

### 7.1 项目定位

Day5 项目是一个 **可运行的企业级 RAG Demo**，独立于前四天，位于：

```
com.zero.ai.agentstudy.day05rag
```

它对外提供两组核心接口：

1. **文档写入接口**：把一段文本 / 一个文档灌入知识库（解析 → 切分 → Embedding → 存储）。
2. **智能问答接口**：输入一个问题，系统召回相关资料并让 LLM 基于资料作答，同时返回引用出处。

### 7.2 企业分层目录（本项目将逐章建立）

```
day05rag/
├── docs/                     # 学习资料（本 README + 章节笔记 + 架构 + TODO）
│   ├── README.md
│   ├── chapters/             # chapter-01.md ~ chapter-08.md
│   ├── ARCHITECTURE.md
│   └── TODO.md
├── controller/               # RagController：对外 HTTP 接口
├── service/                  # RagService：编排「索引流」与「问答流」
├── retriever/                # DocumentRetriever：召回 Top-K 相关块
├── embedding/                # EmbeddingClient：文本 → 向量
├── vectorstore/              # VectorStore：向量的存储与相似检索（内存版 / pgvector 版）
├── parser/                   # DocumentParser：原始文档 → 纯文本
├── splitter/                 # TextSplitter：纯文本 → Chunk 列表
├── entity/                   # Document / Chunk / EmbeddedChunk 等领域对象
├── dto/                      # 请求/响应 DTO
├── config/                   # RagProperties 等配置
└── util/                     # 相似度计算、Token 估算等工具
```

> 设计原则：**每一层只做一件事**。这样以后要把内存向量库换成 pgvector、把切分器换成语义切分，只需替换一层，其他层完全不动。这就是「可替换、可演进」的企业架构。

### 7.3 运行环境

- JDK 17、Maven、Spring Boot 3.4.5、Spring AI 1.0.0（项目 `pom.xml` 已具备）。
- 一个 OpenAI 兼容的服务（本机 LM Studio：`http://127.0.0.1:1234`，见 `application.yml`）。
- Embedding：优先复用同一个 OpenAI 兼容服务的 embeddings 接口；若本地无 Embedding 模型，本项目提供一个「本地可离线运行的哈希向量」降级实现，保证 Demo 无外部依赖也能跑通（第三章说明其原理与局限）。
- 向量存储：Day5 默认使用「内存向量库」，方便零依赖跑通；第五章讲解如何平滑升级到 pgvector。

### 7.4 如何运行（占位，随代码章节更新）

```bash
# 1. 启动本地大模型服务（LM Studio），确保 127.0.0.1:1234 可用
# 2. 启动 Spring Boot
mvn spring-boot:run
# 3. 写入知识
curl -X POST http://localhost:8080/api/day05/rag/ingest -H "Content-Type: application/json" \
  -d '{"title":"请假制度","content":"员工年假为10天，需提前3天申请……"}'
# 4. 提问
curl -X POST http://localhost:8080/api/day05/rag/ask -H "Content-Type: application/json" \
  -d '{"question":"我一年有几天年假？"}'
```

---

## 八、术语表（速查）

| 术语 | 含义 |
| --- | --- |
| LLM | 大语言模型，如 GPT、Qwen、DeepSeek |
| RAG | 检索增强生成，先检索资料再生成答案 |
| Embedding | 把文本转成固定长度向量的过程/结果 |
| 向量维度 | 一个 Embedding 有多少个数字，如 768、1024、1536 |
| Chunk | 把长文档切成的一小段文本 |
| Chunk Overlap | 相邻 Chunk 之间重叠的字数，防止句意被切断 |
| Top-K | 召回时取相似度最高的前 K 个 Chunk |
| 余弦相似度 | 衡量两个向量方向相近程度，取值 -1~1，越大越相似 |
| Retriever | 负责根据问题召回相关 Chunk 的组件 |
| Vector Store | 存储向量并支持相似检索的数据库 |
| Rerank | 对召回结果二次精排，提升相关性 |
| Hybrid Search | 关键词检索 + 向量检索融合 |
| 幻觉(Hallucination) | 模型一本正经地编造错误内容 |

---

## 九、今日面试题预告（每章 chapter-xx.md 会给完整答案）

1. 为什么企业不直接微调模型，而用 RAG？
2. RAG 的完整数据流是怎样的？离线和在线分别做了什么？
3. Embedding 的本质是什么？为什么相似文本向量距离近？
4. Chunk 大小和 overlap 如何选择？切太大 / 太小分别有什么问题？
5. 向量数据库怎么选？pgvector、Milvus、Qdrant 各适合什么场景？
6. RAG 回答不准，你会从哪几个环节排查？
7. 什么是 Rerank？什么是 Hybrid Search？为什么需要它们？
8. 多租户 RAG 如何做知识隔离？
9. RAG 的成本主要花在哪？如何优化？
10. RAG 和 Agent、Memory、Function Calling 的关系是什么？

---

## 十、学习方式说明

本项目采用「大学教授 + 企业导师 + Pair Programming」方式，**一章一章推进**：

- 每讲完一章 → 生成对应的 `docs/chapters/chapter-0x.md` 学习笔记。
- 涉及代码的章节 → 在对应目录生成带完整注释的企业级代码，并解释「为什么需要这个类」。
- 每章讲完 **暂停**，等你确认后再进入下一章。

> 下一步：进入 **第一章 —— 为什么需要 RAG**。
