# Chapter 10 · Grounding 与 Verification 防幻觉

> 引用编号合法 ≠ 答案内容真被引用片段支持。上一章保证「[1] 指向真实存在的片段」，本章保证「标了 [1] 的那句话，真的能从片段 [1] 里推出来」——逐句核验，让幻觉无处遁形。

---

## 一、为什么学（Why）

### 1.1 合法引用也能撒谎

Ch09 之后，LLM 的答案已经带上了合法引用。但看这个例子：

- 片段 [1]：「全职员工年假为 15 天。」
- LLM 答案：「全职员工年假为 **20** 天 [1]。」

引用 `[1]` 完全合法（片段真实存在），但**答案内容和片段矛盾**——LLM 把 15 悄悄写成了 20，还煞有介事地标了来源。Ch09 的编号校验对此**无能为力**，因为它只查「编号存不存在」，不查「内容对不对得上」。

**这就是 Grounding（接地）要解决的：答案的每个论断，必须真正「扎根」在检索到的证据里。**

### 1.2 幻觉的三种典型形态

| 形态 | 例子 | 检测手段 |
| --- | --- | --- |
| **无中生有** | 上下文没提，答案硬编造 | 无引用 / 引用与内容无关 |
| **张冠李戴** | 引用了片段但内容对不上（15→20） | 蕴含核验（本章重点） |
| **过度延伸** | 片段说 A，答案推断出没依据的 B | 蕴含核验 + 保守生成 |

### 1.3 本章要解决的三个命题

1. **怎么判断「一句话被证据支持」**：什么是蕴含（Entailment）核验？
2. **整段答案怎么定级**：逐句核验后，如何汇总成 SUPPORTED / PARTIAL / UNSUPPORTED？
3. **不合格答案怎么处置**：UNSUPPORTED 直接拦截还是降级？置信度怎么算？

---

## 二、是什么（What）

### 2.1 核心概念：蕴含核验（Entailment / NLI）

自然语言推理（NLI）判断「前提（证据）」与「假设（答案句）」的关系：

```
前提（证据片段）：全职员工年假为 15 天。
假设（答案句）  ：全职员工年假为 20 天。
             ↓ NLI 判断
关系：CONTRADICTION（矛盾）——不被支持
```

三种关系：

- **ENTAILMENT（蕴含）**：证据能推出答案句 → 支持。
- **NEUTRAL（中立）**：证据没提，推不出也不矛盾 → 不支持（可能幻觉）。
- **CONTRADICTION（矛盾）**：证据与答案句冲突 → 严重幻觉。

### 2.2 答案的三级 Grounding 状态

逐句核验后，按「有多少句被证据支持」给整段答案定级：

| 状态 | 定义 | 含义 |
| --- | --- | --- |
| **SUPPORTED** | 所有关键句都被证据支持 | 可放心输出 |
| **PARTIAL** | 部分句被支持、部分无据 | 输出但标注「部分内容待核实」 |
| **UNSUPPORTED** | 关键句无据 / 存在矛盾 | 拦截，转 Knowledge Gap |

### 2.3 置信度（Confidence）的构成

置信度不是拍脑袋，而是可量化的加权：

```
confidence = w1 · 被支持句占比
           + w2 · 引用片段平均相关度（来自 Rerank/RRF score）
           + w3 · 是否存在矛盾句的惩罚（有矛盾则大幅扣分）
```

本章用「被支持句占比」为主、无矛盾为前提做简化演示；生产可接入模型打分。

### 2.4 在推理链中的位置——最后一道闸门

```
Router → Query → Retrieval → Rerank → ACL → Citation → 【Verifier】→ 输出
                                                            ↑
                            本章：逐句核验答案 vs 证据，定级 + 置信度，
                            不合格则拦截，绝不让幻觉出门
```

---

## 三、怎么用（How）

> 代码位于包 `com.zero.ai.agentstudy.day16knowledgeagentv2.verify`，复用 Ch09 `CitedAnswer`/`Citation` 与 core，可独立编译运行。

### 3.1 Grounding 状态枚举

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.verify;

/**
 * 答案接地状态（Grounding）。表征答案被检索证据支持的程度。
 *
 * @author ZeroAi
 */
public enum GroundingStatus {

    /** 所有关键句均被证据支持，可放心输出。 */
    SUPPORTED,

    /** 部分句被支持、部分无据，需标注提醒。 */
    PARTIAL,

    /** 关键句无据或存在矛盾，应拦截并转知识缺口。 */
    UNSUPPORTED
}
```

### 3.2 单句核验结果 SentenceVerdict

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.verify;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;

/**
 * 单句核验结果（不可变）。
 *
 * @param sentence  被核验的答案句
 * @param supported 是否被证据支持
 * @param evidence  支持它的证据片段（未支持时为空串）
 * @author ZeroAi
 */
public record SentenceVerdict(String sentence, boolean supported, String evidence) {

    public SentenceVerdict {
        AgentException.requireText(sentence, "sentence");
        evidence = evidence == null ? "" : evidence;
    }
}
```

### 3.3 验证报告 VerificationReport

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.verify;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;

import java.util.List;

/**
 * 验证报告（不可变）：整段答案的核验结论。
 *
 * @param status     整体接地状态
 * @param confidence 置信度 [0,1]
 * @param verdicts   逐句核验明细
 * @param traceId    全链路追踪 ID
 * @author ZeroAi
 */
public record VerificationReport(
        GroundingStatus status,
        double confidence,
        List<SentenceVerdict> verdicts,
        String traceId
) {

    public VerificationReport {
        AgentException.requireNonNull(status, "status");
        AgentException.requireState(confidence >= 0.0 && confidence <= 1.0,
                com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentErrorCode.INVALID_ARGUMENT,
                "confidence 必须在 [0,1]");
        verdicts = verdicts == null ? List.of() : List.copyOf(verdicts);
        traceId = traceId == null ? "" : traceId;
    }

    /** 是否允许直接输出（SUPPORTED 或 PARTIAL）。 */
    public boolean canOutput() {
        return status != GroundingStatus.UNSUPPORTED;
    }
}
```

### 3.4 验证器端口 AnswerVerifier

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.verify;

import com.zero.ai.agentstudy.day16knowledgeagentv2.citation.CitedAnswer;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;

/**
 * 答案验证器（端口）。逐句核验答案是否被引用证据支持。
 *
 * <p>演示实现用词汇重叠模拟蕴含判断；生产替换为 NLI 模型 / LLM-as-Judge。</p>
 *
 * @author ZeroAi
 */
public interface AnswerVerifier {

    /**
     * 核验带引用的答案，产出验证报告。
     */
    VerificationReport verify(AgentContext context, CitedAnswer answer);
}
```

### 3.5 词汇重叠核验实现 LexicalEntailmentVerifier

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.verify;

import com.zero.ai.agentstudy.day16knowledgeagentv2.citation.CitedAnswer;
import com.zero.ai.agentstudy.day16knowledgeagentv2.citation.Citation;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于词汇重叠的接地验证器（演示版）。
 *
 * <p>思路：把答案按句拆分，逐句与所有引用片段比对——若某句与某片段的
 * 字符重叠率超过阈值，判定为「被支持」。生产中应替换为 NLI 模型或
 * LLM-as-Judge，以捕捉「15 vs 20」这类词面相近但语义矛盾的情况。</p>
 *
 * @author ZeroAi
 */
public final class LexicalEntailmentVerifier implements AnswerVerifier {

    /** 判定「被支持」的重叠率阈值。 */
    private static final double SUPPORT_THRESHOLD = 0.3;

    @Override
    public VerificationReport verify(AgentContext context, CitedAnswer answer) {
        AgentException.requireNonNull(context, "context");
        AgentException.requireNonNull(answer, "answer");

        List<Citation> citations = answer.citations();
        // 去掉正文里的 [n] 标注再按句号切分
        String[] sentences = answer.answer().replaceAll("\\[\\d+]", "").split("[。.!?！？]");

        List<SentenceVerdict> verdicts = new ArrayList<>();
        int supportedCount = 0;
        int keySentences = 0;

        for (String raw : sentences) {
            String sentence = raw.strip();
            if (sentence.isEmpty()) {
                continue;
            }
            keySentences++;
            String hitEvidence = "";
            for (Citation c : citations) {
                if (overlapRatio(sentence, c.snippet()) >= SUPPORT_THRESHOLD) {
                    hitEvidence = c.snippet();
                    break;
                }
            }
            boolean supported = !hitEvidence.isEmpty();
            if (supported) {
                supportedCount++;
            }
            verdicts.add(new SentenceVerdict(sentence, supported, hitEvidence));
        }

        // 定级：全支持=SUPPORTED，零支持=UNSUPPORTED，其余=PARTIAL
        double ratio = keySentences == 0 ? 0.0 : (double) supportedCount / keySentences;
        GroundingStatus status;
        if (ratio >= 1.0) {
            status = GroundingStatus.SUPPORTED;
        } else if (ratio <= 0.0) {
            status = GroundingStatus.UNSUPPORTED;
        } else {
            status = GroundingStatus.PARTIAL;
        }

        // 置信度：被支持句占比 与 引用平均相关度 的均值
        double avgScore = citations.isEmpty() ? 0.0
                : citations.stream().mapToDouble(Citation::score).average().orElse(0.0);
        double confidence = clamp((ratio + avgScore) / 2.0);

        return new VerificationReport(status, confidence, verdicts, context.traceId());
    }

    /** 计算两段文本的字符重叠率（相对较短一方）。 */
    private double overlapRatio(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        var setA = a.chars().boxed().collect(java.util.stream.Collectors.toSet());
        var setB = b.chars().boxed().collect(java.util.stream.Collectors.toSet());
        var inter = new java.util.HashSet<>(setA);
        inter.retainAll(setB);
        return (double) inter.size() / Math.min(setA.size(), setB.size());
    }

    private double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
```

### 3.6 运行演示 Chapter10Demo

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.verify;

import com.zero.ai.agentstudy.day16knowledgeagentv2.citation.CitedAnswer;
import com.zero.ai.agentstudy.day16knowledgeagentv2.citation.Citation;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.TenantId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.UserId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.WorkspaceId;

import java.util.List;
import java.util.Set;

/**
 * Chapter 10 演示：核验「有据」与「无据/编造」两类答案的定级差异。
 *
 * @author ZeroAi
 */
public final class Chapter10Demo {

    public static void main(String[] args) {
        KnowledgeBaseId hr = KnowledgeBaseId.of("kb-hr");
        AgentContext ctx = AgentContext.of(
                TenantId.of("t1"), WorkspaceId.of("w1"), UserId.of("u1"), Set.of("EMPLOYEE"));

        List<Citation> citations = List.of(
                new Citation(1, "h1", hr, "全职员工年假为 15 天", 0.94),
                new Citation(2, "h2", hr, "年假需提前 3 个工作日申请", 0.88));

        AnswerVerifier verifier = new LexicalEntailmentVerifier();

        // 案例 A：有据答案
        CitedAnswer good = new CitedAnswer(
                "全职员工年假为 15 天 [1]。年假需提前 3 个工作日申请 [2]。", citations, ctx.traceId());
        print("有据答案", verifier.verify(ctx, good));

        // 案例 B：夹带编造（"可折算现金"上下文无依据）
        CitedAnswer partial = new CitedAnswer(
                "全职员工年假为 15 天 [1]。未休年假可折算现金。", citations, ctx.traceId());
        print("部分无据", verifier.verify(ctx, partial));
    }

    private static void print(String title, VerificationReport r) {
        System.out.println("== " + title + " ==");
        System.out.println("  status=" + r.status() + ", confidence=" + String.format("%.2f", r.confidence())
                + ", canOutput=" + r.canOutput());
        r.verdicts().forEach(v ->
                System.out.println("  [" + (v.supported() ? "✓" : "✗") + "] " + v.sentence()));
    }
}
```

**运行输出示意**：

```
== 有据答案 ==
  status=SUPPORTED, confidence=0.96, canOutput=true
  [✓] 全职员工年假为 15 天
  [✓] 年假需提前 3 个工作日申请
== 部分无据 ==
  status=PARTIAL, confidence=0.72, canOutput=true
  [✓] 全职员工年假为 15 天
  [✗] 未休年假可折算现金
```

「可折算现金」在证据里找不到支撑 → 被标为无据，整段降为 PARTIAL，置信度下降——**幻觉句被识别**。

---

## 四、真实项目中的落地

1. **NLI 模型 / LLM-as-Judge 替换词汇重叠**：词汇重叠抓不住「15 vs 20」这类语义矛盾。生产用 `bge / mDeBERTa` NLI 或让强模型逐句判 ENTAIL/NEUTRAL/CONTRADICT，端口不变（`AnswerVerifier`）。
2. **矛盾句一票否决**：只要有一句 CONTRADICTION，无论占比多少直接 UNSUPPORTED——矛盾比缺失更危险。
3. **PARTIAL 的处置策略**：对无据句做删除 / 标注「⚠ 待核实」/ 触发二次检索补证据，而非整段丢弃。
4. **置信度接入模型分**：把 NLI 的 entailment 概率、Rerank score、引用覆盖度加权，比纯词面靠谱。
5. **与 Ch11 衔接**：`canOutput()==false`（UNSUPPORTED）时不硬答，转 Knowledge Gap 诚实说「暂无足够依据」。错误码可用 `ANSWER_UNGROUNDED` / `VERIFICATION_FAILED`。

---

## 五、避坑与优化

### 5.1 五个高频坑

| 坑 | 后果 | 正确做法 |
| --- | --- | --- |
| 只校验引用编号(Ch09)就收工 | 「15→20」张冠李戴放行 | 叠加逐句蕴含核验 |
| 用词汇重叠冒充语义核验 | 词面相近的矛盾判成支持 | 生产用 NLI / LLM-as-Judge |
| 矛盾句只扣分不否决 | 严重幻觉混入 PARTIAL 输出 | 矛盾一票否决为 UNSUPPORTED |
| 置信度拍脑袋给定值 | 无法量化、无法调阈值 | 多因子加权可解释 |
| UNSUPPORTED 也硬输出 | 幻觉出门，信任崩塌 | canOutput 为假转 Knowledge Gap |

### 5.2 核验粒度与成本

- **逐句核验成本高**：长答案句数多，NLI 调用量大。可只核验「含数字 / 实体 / 结论」的关键句，寒暄句跳过。
- **批量推理**：把「答案句 × 证据」组成批次一次推理，降低往返延迟。
- **缓存**：相同(答案句, 证据)对的核验结果可缓存。

### 5.3 与生成阶段协同

- **保守生成**：在 Prompt 里要求「无依据不作答、不推断」，从源头减少无据句，降低核验拦截率。
- **核验反哺**：把高频被拦的问题回流到 Ch11 知识缺口、Ch12 反馈，驱动知识库补全。

---

## 小结

- **合法引用 ≠ 内容被支持**：Ch09 查编号，Ch10 查内容——逐句蕴含核验是防「张冠李戴」幻觉的关键。
- **三级状态 + 置信度**：SUPPORTED 放行、PARTIAL 标注、UNSUPPORTED 拦截；置信度多因子可量化。
- **端口可插拔**：演示用词汇重叠，生产无缝替换为 NLI / LLM-as-Judge，`AnswerVerifier` 接口不变。
- **矛盾一票否决、不合格转缺口**：安全优先，宁可说「暂无依据」，绝不让幻觉出门。

> **下一章预告 · Chapter 11 · Knowledge Gap 知识缺口治理**：前面几章多次「转 Knowledge Gap」——那到底什么是知识缺口？如何优雅地承认「我不知道」，并把缺口沉淀为知识库补全的驱动力？下一章揭晓。