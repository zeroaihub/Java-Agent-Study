# Chapter 09 · Citation 引用溯源

> 送进 LLM 的每一条都既相关又有权限了，但用户凭什么信 Agent 说的？本章给答案装上「可点回原文」的引用脚注——让每一句话都有出处，可核查、可追责。

---

## 一、为什么学（Why）

### 1.1 没有引用的答案 = 不可信的答案

想象两个回答：

- A：「根据公司规定，年假是 15 天。」
- B：「根据公司规定，年假是 15 天。[1]」——点击 [1] 跳到《员工手册 v3.2 第 4.2 条》原文。

在企业场景里，A 是**危险**的：员工照着请了 15 天假，结果手册早改成 12 天了，谁负责？B 才是**可用**的：答案有据可查，用户能自己核对，出错也能追溯到原文。

**企业级 Agent 与玩具 Demo 的分水岭之一，就是能不能给出可信引用。**

### 1.2 引用解决三个核心诉求

1. **可信（Trust）**：用户能看到「答案来自哪份文档的哪一段」，而非凭空生成。
2. **可核查（Verifiable）**：用户能点回原文，自行判断 Agent 有没有断章取义。
3. **可追责（Accountable）**：答案出错时，能定位到是「原文就错」还是「Agent 曲解」——这是 Ch10 Verification 的前置。

### 1.3 本章要解决的三个命题

1. **引用长什么样**：一条引用要携带哪些信息才够用（文档、片段、位置、相关度）？
2. **答案和引用怎么绑定**：LLM 生成一段话，如何标注「这句来自 [1]、那句来自 [2]」？
3. **怎么防「假引用」**：LLM 可能编造根本不存在的引用编号，如何校验并剔除？

---

## 二、是什么（What）

### 2.1 引用的数据结构

一条合格的引用（Citation）至少要能回答「**从哪来、是什么、有多相关**」：

```
Citation
├── index      引用序号（[1] [2] ...，供正文标注）
├── chunkId    对应的检索片段 ID（可回溯到 RetrievedChunk）
├── source     来源知识库（KnowledgeBaseId）
├── snippet    片段摘要（展示给用户的原文节选）
└── score      相关度（该片段的最终得分，供排序/展示）
```

### 2.2 答案与引用的绑定方式

主流两种：

| 方式 | 做法 | 优点 | 缺点 |
| --- | --- | --- | --- |
| **行内标注（Inline）** | 正文里插 `[1] [2]`，末尾列引用清单 | 精确到句，用户体验好 | 依赖 LLM 老实标注，易漏标/错标 |
| **整体附注（Footnote）** | 正文不标，末尾统一列出「参考来源」 | 实现简单，不依赖 LLM 标注能力 | 无法定位「哪句来自哪条」 |

> 本章实现 **行内标注 + 引用清单**：LLM 在生成时用 `[n]` 引用第 n 条上下文，我们负责**校验编号合法性**并组装最终 `AgentAnswer`。

### 2.3 「先编号，再生成，后校验」的三段式

```
1. 编号：把 ACL 过滤后的 TopN chunk 编号为 [1..N]，连同内容拼进 Prompt
        ↓
2. 生成：LLM 依据带编号的上下文作答，要求「引用处标 [n]」
        ↓
3. 校验：从答案里抽取所有 [n]，剔除越界/不存在的编号，
        只保留「真实被引用且合法」的 Citation，组装 AgentAnswer
```

第 3 步是**防幻觉引用**的关键：LLM 可能标了 `[9]` 但上下文只有 5 条——这种假引用必须被拦下。

### 2.4 在推理链中的位置

```
Router → Query → Retrieval → Rerank → ACL → 【Citation】→ Verifier
                                                 ↑
                          本章：把「有权限的相关片段」编号、
                          拼 Prompt、校验引用、组装带出处的答案
```

---

## 三、怎么用（How）

> 代码位于包 `com.zero.ai.agentstudy.day16knowledgeagentv2.citation`，复用 Ch05 `RetrievedChunk` 与 core 的 `AgentContext`，可独立编译运行。

### 3.1 引用值对象 Citation

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.citation;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.retrieval.RetrievedChunk;

/**
 * 引用（不可变值对象）。答案中每个 [n] 标注对应一条 Citation。
 *
 * @param index   引用序号（正文用 [index] 标注），从 1 开始
 * @param chunkId 对应检索片段 ID（回溯到 RetrievedChunk）
 * @param source  来源知识库
 * @param snippet 展示给用户的原文节选
 * @param score   该片段最终相关度
 * @author ZeroAi
 */
public record Citation(
        int index,
        String chunkId,
        KnowledgeBaseId source,
        String snippet,
        double score
) {

    public Citation {
        AgentException.requireState(index >= 1,
                com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentErrorCode.INVALID_ARGUMENT,
                "引用序号必须从 1 开始");
        AgentException.requireText(chunkId, "chunkId");
        AgentException.requireNonNull(source, "source");
        snippet = snippet == null ? "" : snippet;
    }

    /**
     * 由带序号的检索片段构造引用。snippet 做长度截断，避免展示过长。
     */
    public static Citation from(int index, RetrievedChunk chunk) {
        AgentException.requireNonNull(chunk, "chunk");
        String content = chunk.content() == null ? "" : chunk.content();
        String snippet = content.length() > 80 ? content.substring(0, 80) + "…" : content;
        return new Citation(index, chunk.chunkId(), chunk.source(), snippet, chunk.score());
    }
}
```

### 3.2 带引用的答案 CitedAnswer

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.citation;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;

import java.util.List;

/**
 * 带引用清单的答案（不可变）。
 *
 * @param answer    正文（含 [n] 行内标注）
 * @param citations 校验后的合法引用清单，与正文 [n] 一一对应
 * @param traceId   全链路追踪 ID
 * @author ZeroAi
 */
public record CitedAnswer(String answer, List<Citation> citations, String traceId) {

    public CitedAnswer {
        answer = answer == null ? "" : answer;
        citations = citations == null ? List.of() : List.copyOf(citations);
        traceId = traceId == null ? "" : traceId;
    }

    /**
     * 是否为「有据答案」——至少命中一条合法引用。
     */
    public boolean isGrounded() {
        return !citations.isEmpty();
    }
}
```

### 3.3 引用组装器 CitationAssembler（编号 + 校验）

这是本章核心：负责**编号上下文**、**从答案抽取引用**、**校验合法性**、**组装 CitedAnswer**。

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.citation;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;
import com.zero.ai.agentstudy.day16knowledgeagentv2.retrieval.RetrievedChunk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 引用组装器：编号上下文 → 抽取答案中的 [n] → 校验 → 组装带引用答案。
 *
 * <p>核心职责是「防假引用」：LLM 可能标注越界/重复编号，这里统一校验剔除，
 * 只保留真实存在且被引用的 Citation。</p>
 *
 * @author ZeroAi
 */
public final class CitationAssembler {

    /** 匹配正文中的 [1] [23] 等引用标注。 */
    private static final Pattern CITE = Pattern.compile("\\[(\\d+)]");

    /**
     * 把片段编号为 [1..N] 拼进 Prompt 上下文（供 LLM 阅读并引用）。
     */
    public String buildContext(List<RetrievedChunk> chunks) {
        AgentException.requireNonNull(chunks, "chunks");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            sb.append('[').append(i + 1).append("] ")
              .append(chunks.get(i).content())
              .append('\n');
        }
        return sb.toString();
    }

    /**
     * 依据 LLM 返回的答案与编号上下文，校验并组装带引用答案。
     *
     * @param context   请求上下文（取 traceId）
     * @param answer    LLM 生成的正文（含 [n] 标注）
     * @param chunks    与编号一一对应的片段（chunks.get(n-1) 即 [n]）
     */
    public CitedAnswer assemble(AgentContext context, String answer, List<RetrievedChunk> chunks) {
        AgentException.requireNonNull(context, "context");
        AgentException.requireNonNull(chunks, "chunks");
        String text = answer == null ? "" : answer;

        // 1. 抽取答案中出现过的所有引用编号（去重、保序）
        Set<Integer> used = new LinkedHashSet<>();
        Matcher m = CITE.matcher(text);
        while (m.find()) {
            used.add(Integer.parseInt(m.group(1)));
        }

        // 2. 校验编号合法性：必须落在 [1..N] 内，越界的一律丢弃（防假引用）
        List<Citation> citations = new ArrayList<>();
        for (int idx : used) {
            if (idx >= 1 && idx <= chunks.size()) {
                citations.add(Citation.from(idx, chunks.get(idx - 1)));
            }
            // 越界编号（如上下文仅 5 条却标 [9]）静默剔除，可在生产中记日志
        }

        return new CitedAnswer(text, citations, context.traceId());
    }
}
```

### 3.4 运行演示 Chapter09Demo

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.citation;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.TenantId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.UserId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.WorkspaceId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.retrieval.RetrievedChunk;

import java.util.List;
import java.util.Set;

/**
 * Chapter 09 演示：编号上下文、模拟 LLM 带引用作答、校验剔除假引用。
 *
 * @author ZeroAi
 */
public final class Chapter09Demo {

    public static void main(String[] args) {
        KnowledgeBaseId hr = KnowledgeBaseId.of("kb-hr");

        // ACL 过滤后送来的有权限片段（相关性已排好）
        List<RetrievedChunk> chunks = List.of(
                new RetrievedChunk("h1", hr, "员工手册第 4.2 条：全职员工年假为 15 天。", 0.94),
                new RetrievedChunk("h2", hr, "年假需提前 3 个工作日申请。", 0.88),
                new RetrievedChunk("h3", hr, "试用期员工不享受年假。", 0.80)
        );

        AgentContext ctx = AgentContext.of(
                TenantId.of("t1"), WorkspaceId.of("w1"), UserId.of("u1"), Set.of("EMPLOYEE"));

        CitationAssembler assembler = new CitationAssembler();

        // 1. 编号上下文（拼进 Prompt）
        System.out.println("=== 编号上下文 ===");
        System.out.print(assembler.buildContext(chunks));

        // 2. 模拟 LLM 返回：正确引用了 [1][2]，但也编造了一个不存在的 [9]
        String llmAnswer = "全职员工年假为 15 天 [1]，需提前 3 个工作日申请 [2]。另见 [9]。";

        // 3. 校验组装
        CitedAnswer result = assembler.assemble(ctx, llmAnswer, chunks);

        System.out.println("\n=== 答案 ===\n" + result.answer());
        System.out.println("isGrounded=" + result.isGrounded());
        System.out.println("=== 合法引用清单（假引用 [9] 已剔除）===");
        for (Citation c : result.citations()) {
            System.out.println("  [" + c.index() + "] " + c.chunkId()
                    + " (score=" + c.score() + ") " + c.snippet());
        }
    }
}
```

**运行输出示意**：

```
=== 编号上下文 ===
[1] 员工手册第 4.2 条：全职员工年假为 15 天。
[2] 年假需提前 3 个工作日申请。
[3] 试用期员工不享受年假。

=== 答案 ===
全职员工年假为 15 天 [1]，需提前 3 个工作日申请 [2]。另见 [9]。
isGrounded=true
=== 合法引用清单（假引用 [9] 已剔除）===
  [1] h1 (score=0.94) 员工手册第 4.2 条：全职员工年假为 15 天。
  [2] h2 (score=0.88) 年假需提前 3 个工作日申请。
```

LLM 编造的 `[9]`（上下文仅 3 条）被**静默剔除**，只保留真实合法的 `[1][2]`——**防假引用**生效。

---

## 四、真实项目中的落地

1. **Prompt 里明确引用契约**：System Prompt 写清「仅依据带编号的上下文作答，每个论断后用 `[n]` 标注来源；上下文无依据则回答不知道」。约束越明确，LLM 越老实。
2. **Citation 携带回跳锚点**：生产中 snippet 之外还应带 `documentId + 段落偏移 / 页码`，前端点击 `[1]` 能直接跳到原文高亮位置。
3. **未被引用的 chunk 处理**：编号了 5 条 LLM 只引了 2 条——正常。可选择在「参考来源」区只列被引用的（清爽），或全列（透明）。
4. **与 Ch10 Verification 衔接**：本章保证「引用编号合法」，但不保证「答案内容真的被引用片段支持」。后者由 Ch10 逐句核验（NLI/蕴含判断）。
5. **无合法引用即降级**：`isGrounded()==false`（一条引用都没命中）时，不应直接把无据答案抛给用户，应转 Ch11 Knowledge Gap。

---

## 五、避坑与优化

### 5.1 五个高频坑

| 坑 | 后果 | 正确做法 |
| --- | --- | --- |
| 信任 LLM 标注不校验 | 假引用 [9] 流到前端，点击 404 | 三段式第 3 步强制校验编号 [1..N] |
| 只做整体附注不行内标注 | 用户无法定位「哪句来自哪条」 | 行内 [n] + 引用清单 |
| snippet 直接塞全文 | 前端展示臃肿、泄露过多原文 | 截断 + 高亮关键句 |
| 无引用也照常输出答案 | 幻觉答案伪装成可信答案 | isGrounded 为假转 Knowledge Gap |
| 引用编号与 chunk 错位 | [1] 点开是别的文档 | 严格约定 chunks.get(n-1) 对应 [n] |

### 5.2 正则抽取的健壮性

- 本章用 `\[(\d+)]` 抽取，注意排除 Markdown 链接 `[text](url)` 等干扰；生产中可要求 LLM 用更独特的标记（如 `【n】` 或 `[[n]]`）降低误匹配。
- 编号去重用 `LinkedHashSet` 保序，保证引用清单顺序与首次出现顺序一致，阅读体验好。

### 5.3 性能与体验优化

- **引用清单缓存**：同一 chunk 被多次问答引用，snippet 可缓存复用。
- **分数用于展示排序**：引用清单可按 score 或按正文出现顺序排——按出现顺序更符合阅读直觉。
- **可信度提示**：结合引用数量与平均 score，给用户「高/中/低可信」提示（为 Ch10 confidence 铺垫）。

---

## 小结

- **无引用的答案不可信**：企业级 Agent 必须让每个论断可点回原文，实现可信 / 可核查 / 可追责。
- **三段式流程**：编号上下文 → LLM 带 [n] 作答 → 校验剔除假引用，第 3 步是防幻觉引用的关键。
- **Citation 要够用**：index / chunkId / source / snippet / score，能回答「从哪来、是什么、多相关」。
- **无合法引用即降级**：`isGrounded()==false` 转 Knowledge Gap，绝不让无据答案伪装可信。

> **下一章预告 · Chapter 10 · Grounding 与 Verification 防幻觉**：引用编号合法 ≠ 答案内容真被引用片段支持。下一章我们逐句核验答案与来源的「蕴含关系」，给答案打上 SUPPORTED / PARTIAL / UNSUPPORTED 状态与置信度——让幻觉无处遁形。