# Day5 动手任务清单：RAG（检索增强生成）

> 按难度分级：⭐ 必做（打基础）｜⭐⭐ 进阶（练工程）｜⭐⭐⭐ 企业挑战（拔高）。
> 建议顺序完成，每完成一项在 `[ ]` 中打勾。

---

## ⭐ 必做（跑通 RAG 完整闭环）

- [ ] **启动服务**：确认本地大模型（OpenAI 兼容接口）就绪，启动 Spring Boot，端口 8080。
- [ ] **写入知识**：POST `/api/day05/rag/ingest`，灌入一段「请假制度」文本，确认返回切分后的 Chunk 数量。
- [ ] **多文档写入**：再灌入一段「报销制度」文本，确认知识库中有两份文档的 Chunk。
- [ ] **基础问答**：POST `/api/day05/rag/ask`，提问「我一年有几天年假？」，确认答案基于灌入的资料。
- [ ] **跨文档问答**：提问「出差报销几天内提交？」，确认能召回第二份文档的内容。
- [ ] **无关问题**：提问「今天天气怎么样？」，确认系统回答「资料中未找到相关信息」而非胡编。
- [ ] **理解索引流**：对照代码，走通 `TextSplitter → EmbeddingClient → VectorStore.add` 链路。
- [ ] **理解问答流**：对照代码，走通 `EmbeddingClient → VectorStore.search → PromptBuilder → LLM` 链路。
- [ ] **观察切分**：修改 ingest 的文本为一段超长内容（>1000 字），观察切分成多少个 Chunk。
- [ ] **观察 Top-K**：灌入 5 份文档后提问，确认只召回最相关的 Top-K 个 Chunk（而非全部）。

---

## ⭐⭐ 进阶（工程化打磨）

- [ ] **调 Chunk 大小**：修改 TextSplitter 的 chunkSize（如 200 → 500），对比切分数量和召回效果。
- [ ] **调 Overlap**：修改 overlap（如 0 → 50），理解重叠对句意完整性的影响。
- [ ] **调 Top-K**：修改检索的 topK（如 3 → 1 → 10），观察对回答质量的影响。
- [ ] **余弦相似度验证**：用 SimilarityUtil 手动计算两个向量的相似度，理解值域 [-1, 1]。
- [ ] **Embedding 降级理解**：阅读 HashEmbeddingClient，理解为什么哈希向量只是降级方案（无语义能力）。
- [ ] **Prompt 结构优化**：修改 PromptBuilder，调整「资料 + 问题」的拼装格式，观察回答差异。
- [ ] **引用出处**：在 AskResponse 中增加 `sources` 字段，返回召回 Chunk 的标题和片段。
- [ ] **多轮追问**：结合 Day4 Memory，让 RAG 问答支持上下文追问（如「那病假呢？」）。

---

## ⭐⭐⭐ 企业挑战（生产级演进）

- [ ] **真实 Embedding**：替换 HashEmbeddingClient 为 OpenAI text-embedding-3-small 或本地 BGE 模型。
- [ ] **真实向量库**：替换 InMemoryVectorStore 为 pgvector（PostgreSQL 扩展），支持持久化。
- [ ] **语义切分**：实现基于段落/标题的切分策略，替代固定大小切分。
- [ ] **Rerank 精排**：对 Top-K 召回结果做 Cross-Encoder 二次排序，提升相关性。
- [ ] **Hybrid Search**：实现 BM25 关键词检索 + 向量检索融合，对比纯向量检索的召回率。
- [ ] **多租户隔离**：为 VectorStore 增加 namespace/tenantId 维度，不同租户知识互不可见。
- [ ] **文档解析**：支持 PDF / Word / Markdown 文件上传，自动解析为纯文本再切分。
- [ ] **缓存优化**：对高频问题的 Embedding 结果做缓存，减少重复计算。
- [ ] **可观测**：增加检索命中率、召回延迟、Token 消耗等 Micrometer 指标。
- [ ] **RAG + Agent**：将 RAG 问答封装为 @Tool，让 Day3 的 Agent 能自动调用知识库。

---

## 自检清单（提交前对照）

- [ ] ingest 和 ask 两个接口均能正常返回，无 500 错误。
- [ ] 写入知识后能正确召回相关内容，答案基于资料而非模型臆造。
- [ ] 无关问题返回「未找到」而非幻觉回答。
- [ ] 理解 RAG 两条数据流：离线索引（切分→Embedding→存储）+ 在线问答（检索→组装→生成）。
- [ ] 理解 Chunk 大小和 Overlap 对召回质量的影响。
- [ ] 理解余弦相似度的含义：值越接近 1，语义越相近。
- [ ] 理解 Embedding 的本质：文本→固定维度浮点数组，语义相近则向量距离近。
- [ ] 理解 RAG 与微调的区别：RAG 知识随时更新、可追溯出处、成本低。
- [ ] 架构分层清晰：替换任何一层（Embedding/VectorStore/Splitter）不影响其他层。
