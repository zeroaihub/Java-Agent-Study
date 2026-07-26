# Day05 RAG 实战（mycode）—— Spring AI + LM Studio 本地真实 RAG

> 这是一个**可本地跑通的真实 RAG**：向量来自真正的语义模型（LM Studio 加载的 Embedding 模型），
> 而不是假向量。整条链路：切分 → 向量化 → 存储 → 召回 → LLM 生成（带出处、防幻觉）。

## 一、整体架构（分层职责）

```
controller/RagController      对外 HTTP 入口（ingest / ask / clear）
      │
service/RagService            总指挥：编排「索引流」和「问答流」
      │
      ├── splitter/TextSplitter        长文本 → 定长重叠 Chunk
      ├── embedding/EmbeddingClient    文本 → 向量（真实调用 LM Studio /v1/embeddings）
      ├── vectorstore/InMemoryVectorStore  内存向量库，余弦相似度 TopK 检索
      ├── retriever/DocumentRetriever  问题向量化 + 相似检索
      └── util/SimilarityUtil          余弦相似度计算
```

- **离线索引流**（ingest）：切分 → 批量向量化 → 存入向量库
- **在线问答流**（ask）：问题向量化 → 相似检索 → 阈值过滤 → 拼 Prompt → LLM 生成

> `embedding/HashEmbeddingClient` 是纯离线的**哈希降级对照实现**（不懂语义），仅用于无模型时验证流程，不参与真实问答。

## 二、前置准备

### 1. JDK 版本（重要）
本项目要求 **JDK 17**。若你的 `mvn -version` 显示 Maven 运行在更高版本 JDK（如 23），
Lombok 注解处理器会失效导致全项目编译报错。编译时请显式指定 JDK 17：

```bash
JAVA_HOME=/path/to/jdk-17 mvn clean compile
```

### 2. LM Studio 需加载「两个」模型
- **一个 Chat 模型**（对话生成，供 Spring AI ChatModel 使用）
- **一个 Embedding 模型**：`qwen3-embedding-0.6b-dwq`（768 维；注意 model id 用 LM Studio `/v1/models` 返回的全小写 id）

在 LM Studio 中启动本地服务器（默认 `http://127.0.0.1:1234`），确保两个模型都已加载。

### 3. 配置项（`src/main/resources/application.yml`）
```yaml
rag:
  embedding:
    base-url: http://127.0.0.1:1234   # LM Studio 地址
    api-key: sk-1234                  # 占位即可
    model: qwen3-embedding-0.6b-dwq  # 与 LM Studio /v1/models 返回的 id 完全一致（区分大小写）
```

## 三、启动

```bash
JAVA_HOME=/path/to/jdk-17 mvn spring-boot:run
```

应用默认端口 `8080`。

## 四、接口与 curl 示例

### 1. 写入知识 `POST /api/day05/rag/mycode/ingest`
```bash
curl -X POST http://localhost:8080/api/day05/rag/mycode/ingest \
  -H "Content-Type: application/json" \
  -d '{"title":"请假制度","content":"员工年假为10天，需提前3天申请。病假需提供医院证明。"}'
```
响应：
```json
{ "title": "请假制度", "chunkCount": 1, "totalInStore": 1 }
```

### 2. 智能问答 `POST /api/day05/rag/mycode/ask`
```bash
curl -X POST http://localhost:8080/api/day05/rag/mycode/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"我一年有几天年假？"}'
```
响应（示例）：
```json
{
  "answer": "根据《请假制度》，员工年假为 10 天，需提前 3 天申请。",
  "references": [
    { "docTitle": "请假制度", "score": 0.82, "snippet": "员工年假为10天..." }
  ]
}
```
> `topK` 可选（默认取配置值），例如 `{"question":"...","topK":3}`。

### 3. 清空知识库 `DELETE /api/day05/rag/mycode/clear`
```bash
curl -X DELETE http://localhost:8080/api/day05/rag/mycode/clear
```

## 五、端到端验证流程

1. 启动 LM Studio，加载 chat + `qwen3-embedding-0.6b-dwq` 两个模型；
2. 用 JDK 17 启动应用；
3. 调 `/ingest` 灌入几条知识；
4. 调 `/ask` 提问，检查：
   - 命中时：答案基于资料，`references` 有出处；
   - 未命中时：模型应明确拒答（"资料中未提及"），而不是编造 —— 这是**防幻觉**设计。

## 六、关键设计说明（为什么这样写）

| 设计 | 原因 |
| --- | --- |
| EmbeddingClient 用独立 WebClient 直连 `/v1/embeddings` | 不依赖 Spring AI EmbeddingModel 自动装配，更稳、更直观，规避版本装配风险 |
| 入库与查询用**同一个** EmbeddingClient | 铁律：向量必须来自同一模型，否则相似度无意义 |
| Chunk 定长滑动窗口 + 重叠（size=300, overlap=60） | 保留跨块上下文，避免答案被切断 |
| 相似度阈值过滤（约 0.3） | 过滤掉不相关片段，降低幻觉 |
| systemPrompt 强约束「只依据资料回答」 | 无资料时明确拒答，防止 LLM 凭空编造 |
| 内存向量库 | 零依赖跑通；未来可平滑替换为 pgvector 等 |

## 七、常见问题

- **编译报大量 Lombok 符号找不到**：Maven 用了高版本 JDK，改用 JDK 17。
- **调用 embedding 超时/报错**：确认 LM Studio 已启动且加载了 embedding 模型，`base-url` 正确。
- **答案总是拒答**：知识库为空或相似度低于阈值，先确认 `/ingest` 成功、问题与资料相关。