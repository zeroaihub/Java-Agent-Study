# 第九章：收官 Demo——分析 GitHub Trending 最热 AI Agent 项目并生成 Markdown

> 前八章造齐了所有零件，本章把它们组装成一个**端到端可运行的真实 Demo**：给 Planning Agent 一个目标——「找出 GitHub Trending 上最热的 AI Agent 项目并生成 Markdown 报告」，看它如何自主拆解、调度、执行、反思、产出结果。最后讲如何接入 ZeroHub AI Agent Platform。

---

## 第一部分：为什么用这个 Demo

这个任务天然覆盖了 Planning Agent 的所有能力，是绝佳的验收用例：

- **需要拆解**（Goal Decomposition）：抓取→筛选→提取信息→总结→排版，多步骤。
- **有依赖**（Dependency）：必须先抓到页面才能提取，先提取才能总结。
- **需要工具选择**（Tool Selection）：抓取用 browser，提取/总结/排版用 llm。
- **会失败**（Failure Recovery）：网络抓取可能失败，需重试；页面结构变化可能需重规划。
- **多步推理**（Multi-Step Reasoning）：每步结果喂给下一步（黑板累积）。
- **有明确产出**：一份结构化的 Markdown 报告，便于验证成败。

---

## 第二部分：是什么（Demo 涉及的真实工具实现）

### 2.1 真实的 BrowserTool（HTTP 抓取）

把 chapter-06 的桩实现换成真实抓取（用 JDK 自带 HttpClient，无需额外依赖）：

```java
package com.zero.ai.agentstudy.day10planningagent.executor.tool;

import com.zero.ai.agentstudy.day10planningagent.core.PlanStep;
import com.zero.ai.agentstudy.day10planningagent.context.PlanningContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** 浏览器工具：抓取 GitHub Trending 页面 HTML。 */
@Component
public class BrowserTool implements Tool {

    @Value("${zero.planning.trending-url:https://github.com/trending?since=daily}")
    private String trendingUrl;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override public String name() { return "browser"; }
    @Override public String description() { return "抓取 GitHub Trending 页面 HTML"; }

    @Override
    public String execute(PlanStep step, PlanningContext ctx) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(trendingUrl))
                .header("User-Agent", "Mozilla/5.0 (PlanningAgent Day10)")
                .timeout(Duration.ofSeconds(15))
                .GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new java.io.IOException("抓取失败，HTTP " + resp.statusCode());  // 抛异常触发重试
        }
        String html = resp.body();
        // 只保留 <article> 仓库块，压缩体积（避免整页 HTML 撑爆 LLM 上下文）
        return extractRepoArticles(html);
    }

    /** 粗提取 trending 仓库区块，减小喂给 LLM 的体积。 */
    private String extractRepoArticles(String html) {
        int start = html.indexOf("<article");
        int end = html.lastIndexOf("</article>");
        if (start >= 0 && end > start) {
            String slice = html.substring(start, Math.min(end + 10, html.length()));
            // 截断过长内容，保留前 20000 字符即可覆盖前若干仓库
            return slice.length() > 20000 ? slice.substring(0, 20000) : slice;
        }
        return html.length() > 20000 ? html.substring(0, 20000) : html;
    }
}
```

### 2.2 LLM 工具承担提取/总结/排版

chapter-06 的 `LlmTool` 已能胜任——它把「目标 + 当前步骤 + 已完成上下文」喂给模型。对本 Demo，提取步骤会让 LLM 从 HTML 里挑出「与 AI Agent 相关的仓库及其 star/描述」，总结步骤生成要点，排版步骤输出最终 Markdown。

### 2.3 配置（application.yml 完整版）

```yaml
spring:
  ai:
    openai:
      base-url: ${OPENAI_BASE_URL:https://api.openai.com}
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: ${OPENAI_MODEL:gpt-4o-mini}
          temperature: 0.2          # 规划/反思低温，稳定可复现

zero:
  planning:
    trending-url: https://github.com/trending?since=daily
    max-steps: 15                  # 全局步数护栏
    max-retry-per-step: 2          # 单步重试次数
    retry-backoff-ms: 500
    default-max-replan: 3          # 默认重规划上限
    default-timeout-ms: 120000     # 默认任务超时 2 分钟

server:
  port: 8080

logging:
  level:
    com.zero.ai.agentstudy.day10planningagent: INFO
```

---

## 第三部分：怎么用（端到端跑通）

### 3.1 启动

```bash
export OPENAI_API_KEY=sk-xxxx
export OPENAI_BASE_URL=https://your-openai-compatible-endpoint
mvn spring-boot:run
```

### 3.2 发起任务

```bash
curl -X POST http://localhost:8080/api/day10/planning/run \
  -H "Content-Type: application/json" \
  -d '{
        "goal": "分析 GitHub Trending(daily) 上与 AI Agent 相关的最热门项目，选出 Top 3，为每个项目给出名称、star 数、一句话简介，最后生成一份结构化 Markdown 报告",
        "maxSteps": 15,
        "maxReplan": 3,
        "timeoutMs": 120000
      }'
```

### 3.3 Agent 内部会发生什么（典型执行轨迹）

```
[PLAN]    LLM 拆解目标 → 计划：
          step-1 抓取 GitHub Trending 页面 (browser, HIGH, deps:[])
          step-2 从 HTML 提取仓库列表   (llm, HIGH, deps:[step-1])
          step-3 筛选 AI Agent 相关项目 (llm, HIGH, deps:[step-2])
          step-4 选 Top3 并整理信息      (llm, MEDIUM, deps:[step-3])
          step-5 生成 Markdown 报告      (llm, MEDIUM, deps:[step-4])
[READY]   环检测通过

[EXECUTE] step-1 browser 抓取 → 若网络抖动失败，重试(500ms,1000ms) → 成功，写入黑板
[REFLECT] CONTINUE
[EXECUTE] step-2 llm 提取仓库列表（读黑板里的 HTML）→ 成功
[REFLECT] CONTINUE
[EXECUTE] step-3 llm 筛选 AI Agent 相关 → 成功
[REFLECT] CONTINUE
[EXECUTE] step-4 llm 选 Top3 → 成功
[REFLECT] CONTINUE
[EXECUTE] step-5 llm 排版 Markdown → 成功
[REFLECT] CONTINUE
[SUCCEEDED] 共 5 步，重规划 0 次
```

### 3.4 若中途出错（自我纠错演示）

假设 step-2 提取时 LLM 发现 HTML 结构不含有效仓库（抓到了登录页）：
```
[EXECUTE] step-2 → 结果异常/为空
[REFLECT] LLM 反思：结果无有效数据，可能抓取内容不对 → REPLAN
[RE_PLANNING] 保留已完成成果，重规划：在 step-1 前插入「换用带 since 参数的 URL 重新抓取」
[READY] 继续执行新计划
```
这就是「Self-Correction + Re-Planning」在真实场景的体现。

### 3.5 返回结果示例（RunResponse）

```json
{
  "finalState": "SUCCEEDED",
  "plan": "计划[plan-xxx] 目标:分析GitHub Trending...\n  ✓ step-1 抓取页面 [DONE]\n  ✓ step-2 提取仓库 [DONE]\n  ✓ step-3 筛选AIAgent [DONE]\n  ✓ step-4 选Top3 [DONE]\n  ✓ step-5 生成报告 [DONE]",
  "summary": "step-5 输出：# GitHub Trending AI Agent 项目日报\n\n## Top 1: xxx (⭐ 12,345)\n简介：...\n\n## Top 2: yyy (⭐ 8,900)\n...",
  "steps": 5,
  "replans": 0
}
```

`summary` 里的最后一步输出即为最终的 Markdown 报告。

---

## 第四部分：用在哪（接入 ZeroHub AI Agent Platform）

Day10 模块是独立可运行的（不改前九天代码），接入 ZeroHub 有三种方式：

### 4.1 方式一：REST 集成（最简单）

ZeroHub 主平台通过 HTTP 调用 Day10 的 `POST /api/day10/planning/run`，把 Planning Agent 当作一个「规划服务」。适合微服务架构，模块间松耦合。

### 4.2 方式二：作为工具接入上层 Agent

把整个 Planning Agent 封装成 ZeroHub 的一个「高级工具」——上层 Agent 遇到「需要多步规划的复杂子目标」时，调用 Planning Agent 处理，结果返回上层。这体现了 Agent 的分层组合（Agent 套 Agent）。

```java
// 在 ZeroHub 侧把 Planning Agent 包成一个 Tool
public class PlanningAgentTool implements ZeroHubTool {
    private final PlanningService planningService;
    public String execute(String subGoal) {
        PlanningContext ctx = planningService.run(Goal.of(subGoal, 15, 3, 120000));
        return ctx.completedSummary();
    }
}
```

### 4.3 方式三：Bean 直接注入（同进程）

若 Day10 与 ZeroHub 在同一 Spring 应用，直接 `@Autowired PlanningService` 调用，零网络开销。

### 4.4 复用前九天成果的建议

- Day 记忆模块 → 可作为 `MemoryTool` 接入，让 Planning Agent 记住历史任务。
- Day 工具调用模块 → 已有的工具可直接实现 `Tool` 接口纳入 `ToolRegistry`。
- Day RAG 模块 → 作为 `RagTool`，让规划时能检索知识。
- 复用方式统一：实现 `Tool` 接口 + `@Component`，注册表自动收录，主循环零改动。

---

## 第五部分：避坑指南

1. **抓取内容要压缩再喂 LLM**。整页 HTML 几十万字符会撑爆上下文、烧爆 token。只保留 `<article>` 区块并截断。

2. **User-Agent 必须设**。GitHub 对无 UA 请求可能拒绝，导致抓到异常页。

3. **抓取失败要抛异常**。让 Executor 的重试机制接管，而非返回空字符串（空字符串会被当成功）。

4. **温度要低**。规划/反思用 temperature 0.2 左右，保证稳定可复现；创意类才用高温。

5. **maxSteps 别设太小**。本 Demo 至少 5 步，加上可能的重规划，建议 ≥12，否则预算护栏会提前中断。

6. **依赖要正确声明**。step-2 必须 deps:[step-1]，否则调度器可能在没抓到页面时就去提取。

7. **别把 API Key 写进代码/yml**。用环境变量 `${OPENAI_API_KEY}`，避免泄露。

8. **网络受限环境准备降级**。抓取不通时，Demo 可退化为「读取本地样例 HTML」，保证教学可跑通。

---

## 第六部分：完整验收清单

跑通本 Demo，即验证了 Day10 全部学习目标：

- [x] Task Planning——LLM 拆出 5 步计划
- [x] Goal Decomposition——目标分解为可执行子任务
- [x] Task Scheduler——按依赖 + 优先级调度
- [x] Dynamic Planning——出错时增量重规划
- [x] Reflection——每步后反思裁决
- [x] Self-Correction——REPLAN/RETRY 自我纠错
- [x] Re-Planning——保留成果的增量重规划
- [x] Tool Selection——browser/llm 工具选择
- [x] Multi-Step Reasoning——黑板累积多步推理
- [x] Task Priority——优先级排序
- [x] Plan Execution——执行器落地
- [x] Failure Recovery——重试 + 重规划双层恢复

---

## 全篇总结

从 chapter-01 的全景，到 chapter-02 的概念地基，chapter-03~04 的领域模型与黑板，chapter-05 的调度，chapter-06 的执行与工具，chapter-07 的反思闭环，chapter-08 的生产进阶，到本章的端到端 Demo，我们完整实现了一个企业级 Planning Agent：

```
Goal → Planner(拆解) → Scheduler(依赖+优先级调度) → Executor(工具+重试)
        ↑                                              ↓
        └──── Reflector(四裁决) ← Observation(黑板) ←──┘
              CONTINUE/RETRY/REPLAN/ABORT
        全程 PlanState 状态机驱动 + 预算护栏兜底
```

**核心记忆**：Planning Agent = 拆解 + 调度 + 执行 + 反思 + 重规划的闭环，用状态机驱动、预算护栏兜底、工具可插拔、组件可降级。掌握它，你就掌握了构建自主 Agent 的核心工程范式。

**恭喜完成 Day10！** 你已经从「会调 LLM」进阶到「会编排能自我纠错的多步 Agent」。

---

## FAQ

**Q1：Demo 抓不到 GitHub 怎么办（网络受限）？**
准备一份本地样例 HTML，BrowserTool 在抓取失败时降级读本地文件，保证教学环境可跑通。

**Q2：为什么不用现成爬虫库（Jsoup）？**
用 JDK HttpClient + 字符串截取零额外依赖，聚焦 Planning 逻辑本身。生产里当然可用 Jsoup 精确解析。

**Q3：如何让报告质量更高？**
提升排版步骤的 prompt（给 Markdown 模板）、启用 chapter-08 的 Tree Search 择优计划、给 LlmTool 更强的模型。

---

## 面试高频题

1. 这个 Demo 如何覆盖 Planning Agent 的全部核心能力？
2. 抓取到的 HTML 为什么要压缩后再给 LLM？
3. 如何把一个完整的 Planning Agent 作为「工具」接入上层 Agent？
4. 端到端任务中，依赖声明错误会导致什么问题？
5. 网络受限时如何保证 Agent 仍可演示/降级运行？

---

## 扩展阅读

- JDK HttpClient 官方文档
- Spring AI ChatClient 结构化输出与 Prompt 工程
- Agent 分层组合（Multi-Agent / Agent-as-Tool）模式
- GitHub REST API（比抓 HTML 更稳定的数据源，可作进阶替换）