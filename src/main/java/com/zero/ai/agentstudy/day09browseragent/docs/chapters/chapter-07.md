# 第 7 章 · 自然语言 Agent 编排

> 本章目标：把前六章的所有零件——引擎、池、门面、工具、ChatClient——组装成一个真正能听懂人话的 Browser Agent。你将理解：Agent 的本质公式是什么？`chatClient.prompt().tools()` 如何把工具挂到对话里？为什么一句"打开京东总结活动"能自动变成"开页面→读正文→总结"的多步操作？以及如何用 `BrowserController` 把这一切暴露成 REST 接口，让前端/客户端能调用。

---

## 一、为什么需要 Agent 编排（Why）

到第 6 章为止，我们有了：
- 浏览器工具（`BrowserTools` 的 9 个 `@Tool`）；
- 一个配好人设的 ChatClient（`day09ChatClient`）。

但它们还是**两个分开的零件**。工具挂在那里，ChatClient 也在那里，中间缺一个"编排大脑"把它们串起来，并对外提供一个"输入自然语言 → 输出结果"的统一入口。

设想没有编排层会怎样：调用方得自己写"先调 ChatClient、把 BrowserTools 挂上去、处理多轮工具调用循环、拿最终结果"——这些样板代码散落各处，无法复用。

**所以我们需要 `BrowserAgentService`**——它是 Agent 的"编排中枢"，一个 `run(instruction)` 方法就封装了"LLM + Tools + 多轮循环"的全套逻辑。调用方只管传自然语言，剩下的它全包了。

---

## 二、是什么：Agent 的本质公式（What）

看 [`BrowserAgentService`](../../service/BrowserAgentService.java:22) 的类注释，一句话点出了 Agent 的本质：

```java
/**
 * BrowserAgentService —— Day09 的「浏览器智能体」，把 LLM 与 Browser Tool 组合起来。
 *
 * Agent = LLM(大脑) + Tools(手) + 循环。用户用自然语言下达任务（如
 * "帮我打开 example.com 并总结内容"），LLM 自主决定调用哪个 @Tool、按什么
 * 顺序调用，框架执行浏览器动作后把结果回填，LLM 继续推理直到任务完成。
 */
```

**记住这个公式：Agent = LLM（大脑）+ Tools（手）+ 循环。**

- **LLM（大脑）**：负责理解意图、规划步骤、决定调哪个工具；
- **Tools（手）**：负责真实执行浏览器动作；
- **循环（Loop）**：这是 Agent 与"单次问答"的最大区别——LLM 调用工具 → 拿到结果 → 再推理 → 可能再调工具 → …… 直到任务完成。**多轮自主循环**才叫 Agent。

举个例子，用户说"打开京东首页，总结一下有什么活动"：
1. LLM 推理："我需要先打开页面" → 调用 `openWebPage("https://jd.com")`；
2. 框架执行，返回标题 → 回填给 LLM；
3. LLM 推理："我还需要页面内容才能总结" → 调用 `readPageText("https://jd.com")`；
4. 框架执行，返回正文（截断 3000 字）→ 回填给 LLM；
5. LLM 推理："内容够了，可以总结了" → 生成最终中文答复，循环结束。

**用户只说了一句话，LLM 自主完成了两步工具调用 + 总结。** 这就是"自然语言 → 多步浏览器操作"的魔法，也是 Agent 的核心价值。

---

## 三、怎么用：编排的实现（How）

### 3.1 依赖注入：把大脑和手拿到手

```java
@Slf4j
@Service
public class BrowserAgentService {

    private final ChatClient chatClient;
    private final BrowserTools browserTools;

    public BrowserAgentService(@Qualifier("day09ChatClient") ChatClient chatClient,
                               BrowserTools browserTools) {
        this.chatClient = chatClient;
        this.browserTools = browserTools;
    }
}
```

两个依赖：
- **ChatClient**：注意 `@Qualifier("day09ChatClient")`——精确注入第 6 章定义的独立 Bean，避免与前八天的 ChatClient 冲突。这就是第 6 章讲的"独立 Bean 名 + @Qualifier"隔离策略的落地；
- **BrowserTools**：第 6 章的工具集，等下要挂到对话里。

### 3.2 核心方法 run()：一行 prompt 链串起一切

```java
public String run(String instruction) {
    log.info("[Day09][Agent] 收到任务: {}", instruction);
    String answer = chatClient.prompt()
            .system("""
                    你是一个浏览器自动化助手，可以通过工具真实地操作浏览器。
                    请根据用户意图，选择合适的浏览器工具完成任务；
                    如果需要页面内容再回答，请先调用读取工具获取内容再总结。
                    回答使用简洁中文。
                    """)
            .user(instruction)
            // 把浏览器工具挂到本次对话，LLM 可按需自主调用
            .tools(browserTools)
            .call()
            .content();
    log.info("[Day09][Agent] 任务完成");
    return answer;
}
```

这条流式调用链是全章的精华，逐段拆解：

**① `.system(...)` —— 设置本次任务的系统提示词**
这里的 system 比第 6 章 ChatClient 的 `defaultSystem` 更具体，给了 LLM 三条明确指引：
- "选择合适的浏览器工具完成任务"——鼓励用工具；
- "如果需要页面内容再回答，请先调用读取工具获取内容再总结"——**这句是关键**，它引导 LLM 先读内容再总结，正是上面例子里"先 open 再 readText"多步循环的行为来源；
- "回答使用简洁中文"——约束输出风格。

**② `.user(instruction)` —— 传入用户的自然语言任务**
就是用户那句"打开京东总结活动"。

**③ `.tools(browserTools)` —— 把工具挂到本次对话（灵魂所在）**
这一行把 `BrowserTools` 的 9 个 `@Tool` 全部注册进本次对话。Spring AI 会自动：
- 扫描 `browserTools` 里所有 `@Tool` 方法；
- 生成 JSON Schema 描述；
- 连同 system + user 一起发给 LLM；
- 当 LLM 返回工具调用请求时，**自动反射执行对应方法，并处理多轮回填循环**。

**你没有写任何循环代码，`.tools()` + `.call()` 就帮你处理了整个 Agent 循环。** 这就是 Spring AI 的强大之处。

**④ `.call().content()` —— 发起调用并取最终文本**
`.call()` 触发整个"发送→模型推理→工具调用→回填→再推理→……"的循环，直到 LLM 给出不含工具调用的最终答复。`.content()` 取出这段最终文本返回。

**一句话总结这条链**：设人设 → 传任务 → 挂工具 → 发起循环 → 取结果。**整个 Agent 的编排逻辑就浓缩在这五个链式调用里**，简洁得惊人。

### 3.3 REST 暴露：BrowserController

有了 `BrowserAgentService`，还需要把它暴露成 HTTP 接口。看 [`BrowserController`](../../controller/BrowserController.java:29)：

```java
@Slf4j
@RestController
@RequestMapping("/day09/browser")
public class BrowserController {

    private final BrowserActionService actionService;
    private final BrowserAgentService agentService;

    public BrowserController(BrowserActionService actionService,
                             BrowserAgentService agentService) {
        this.actionService = actionService;
        this.agentService = agentService;
    }
    // ...
}
```

Controller 提供**两类接口**，这是很好的分层设计：

**第一类：原子操作接口（/action/*）—— 直接调门面，绕过 LLM**

```java
@GetMapping("/action/open")
public R<String> open(@RequestParam String url) {
    return R.ok(actionService.openPage(url));
}

@GetMapping("/action/text")
public R<String> text(@RequestParam String url) {
    return R.ok(actionService.getText(url));
}

@PostMapping("/action/click")
public R<String> click(@RequestParam String url, @RequestParam String selector) {
    return R.ok(actionService.click(url, selector));
}
// 还有 /action/html、/action/screenshot、/action/fill、/action/wait
```

这些接口**直接调用第 4 章的门面**，不经过 LLM。用途：
- **单元验证**：测试某个浏览器动作是否正常，不想烧 LLM 的钱；
- **确定性工作流编排**：当你明确知道"就是要点这个按钮"，不需要 LLM 决策时，直接调原子接口更快更省。

**第二类：Agent 接口（/agent/run）—— 走 LLM 自主编排**

```java
@PostMapping("/agent/run")
public R<String> run(@Valid @RequestBody AgentTaskRequest request) {
    return R.ok(agentService.run(request.getInstruction()));
}
```

这个接口接受自然语言（封装在 `AgentTaskRequest` 里，`@Valid` 做参数校验），交给 `BrowserAgentService.run()` 让 LLM 自主编排。用途：**面向终端用户的"说人话就能用"入口**。

**两类接口的哲学**：
| 接口类型 | 是否经过 LLM | 适用场景 | 成本/速度 |
| --- | --- | --- | --- |
| /action/* 原子接口 | 否 | 确定性操作、测试验证、固定工作流 | 快、省 |
| /agent/run Agent接口 | 是 | 模糊意图、需自主决策、终端用户 | 慢、烧 Token |

**设计心法**：不是所有场景都需要 LLM。**能用确定性接口解决的，就别烧 LLM 的钱**——这是企业级 Agent 的成本意识。

注意路径前缀 `@RequestMapping("/day09/browser")`，与前八天的 Controller 路径隔离，多模块共存不冲突。

---

## 四、真实项目：ZeroHub 的 Agent 编排实践

在 ZeroHub，Browser Agent 的编排层沉淀了这些实践：

| 实践 | 做法 | 价值 |
| --- | --- | --- |
| 双入口设计 | 同时暴露原子接口 + Agent 接口 | 内部工作流用原子接口（快省），对客服/运营用 Agent 接口（灵活） |
| system 提示词工程 | 用 system 引导"先读内容再总结"等行为 | 让模型的工具调用顺序更符合业务预期 |
| 参数校验 | AgentTaskRequest 用 @Valid 校验 instruction 非空 | 挡住空指令，避免浪费一次 LLM 调用 |
| 全链路日志 | Agent 入口 + 每个 @Tool 都打日志 | 出问题能还原"模型调了哪些工具、什么顺序" |
| 成本控制 | 明确场景走原子接口，模糊场景才走 Agent | 大幅降低 LLM Token 消耗 |
| 超时与降级 | Agent 调用设整体超时，超时降级为原子操作或报错 | 防止 LLM 多轮循环无限拖延 |

**核心心法**：Agent 编排的价值不在"炫技让 LLM 干一切"，而在"该用 LLM 时用，不该用时果断走确定性路径"。**智能与成本的平衡，才是工程成熟度的标志。**

---

## 五、避坑清单（≥12 条）

1. **别忘了 `.tools()`**：如果漏掉这一行，LLM 就"没有手",只能纯文本回答，无法真正操作浏览器。这是最常见的低级错误。

2. **ChatClient 注入必须加 @Qualifier**：多模块环境下不加 `@Qualifier("day09ChatClient")` 会因多个 Bean 而注入失败或注入错对象。

3. **system 提示词要引导行为**："先读内容再总结"这类引导能显著改善模型的工具调用顺序。别只写"你是助手"这种空话。

4. **不是所有场景都要走 LLM**：确定性操作走 /action 原子接口，又快又省。滥用 Agent 接口是烧钱的元凶。

5. **Agent 接口要设整体超时**：LLM 多轮循环可能拖很久（甚至陷入反复调工具）。必须设超时上限,超时降级。

6. **入参必须校验**：`@Valid` 校验 instruction 非空。空指令会白白浪费一次 LLM 调用。

7. **全链路日志不可省**：Agent 入口 + 每个 Tool 都要打日志，否则出问题无法还原"模型到底做了什么"。

8. **Controller 路径要隔离**：`/day09/browser` 前缀避免与其它模块路由冲突。

9. **别在 Controller 写业务逻辑**：Controller 只做"收请求→调 Service→返 R"，编排逻辑全在 `BrowserAgentService`。保持 Controller 薄。

10. **警惕 LLM 幻觉调用**：模型可能调用不存在的工具或传错参。要在门面层做好参数校验和异常兜底（第4章已做）。

11. **返回统一用 R 包装**：`R.ok(...)` 统一响应结构，便于前端处理成功/失败。别裸返 String。

12. **Agent 结果可能不确定**：同样的指令，LLM 每次的工具调用路径可能不同。对确定性要求高的场景不要用 Agent，用固定工作流。

13. **敏感任务要人工确认**：如"自动下单""转账"这类高危操作，Agent 编排前应加人工确认环节，不能让 LLM 全自动执行不可逆操作。

---

## 六、本章小结

本章我们把所有零件组装成了完整的 Browser Agent：

- **Agent 本质公式**：Agent = LLM（大脑）+ Tools（手）+ 循环（多轮自主调用）；
- **BrowserAgentService 是编排中枢**：`run(instruction)` 一个方法封装全套逻辑；
- **`.prompt().system().user().tools().call().content()`**：五个链式调用串起整个 Agent 循环，`.tools(browserTools)` 是灵魂；
- **多步循环自动化**：一句"打开京东总结活动"自动变成"开页面→读正文→总结"，无需手写循环；
- **BrowserController 双入口**：原子接口（/action/*，直调门面、快省）+ Agent 接口（/agent/run，走 LLM、灵活）；
- **成本意识**：能用确定性接口解决的，不烧 LLM 的钱。

**一句话记住本章**：Agent 编排的精髓，是用几行 prompt 链把"大脑+手+循环"优雅串起，同时保留"确定性直调"的省钱后门——智能与成本的平衡才是工程之道。

> **下一章预告**：至此，一个能听懂人话、能真实操作浏览器的 Agent 已经跑起来了。但从"能跑"到"生产可用",还有一段距离。**第 8 章《企业实战与扩展》**将收官：如何用 StorageState 持久化登录态免去反复登录？如何用 Redis 做分布式会话？Docker 部署要注意什么？如何做重试、限流、可观测性？以及全书避坑总汇——帮你把这个 Demo 真正推向生产。