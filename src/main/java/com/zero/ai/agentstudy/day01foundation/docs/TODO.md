# Day01 TODO 清单

记录本模块的学习练习、待办事项与优化方向。完成后勾选。

---

## 一、学习任务

- [x] 阅读第一章：为什么 Agent 需要 Tool
- [x] 阅读第二章：LLM 核心原理
- [x] 阅读第三章：Agent 核心公式与 Agent Loop
- [x] 阅读第四章：搭建 Java AI 开发环境
- [x] 阅读第五章：完成第一个 AI Demo
- [x] 阅读第六章：企业级 AI 应用架构
- [ ] 完成各章末尾的思考题

---

## 二、动手实践

- [x] 落地五大功能源码（Controller / Service / Config / DTO / common）
- [x] `mvn clean compile` 编译通过
- [ ] 配置 `OPENAI_API_KEY` 并启动应用
- [ ] curl 调用 `/api/day01/chat` 跑通普通聊天
- [ ] 测试动态 System Prompt 覆盖效果
- [ ] 测试不同 temperature（0.1 vs 1.5）的回答差异
- [ ] 故意传空 message，验证 400 参数校验
- [ ] 故意用错误 API Key，验证 500 兜底异常

---

## 三、练习与拓展

- [ ] 给接口增加 `maxTokens` 参数并透传给 `OpenAiChatOptions`
- [ ] 实现流式响应接口（`stream()` + SSE），对接 static/stream.html
- [ ] 为 ChatService 增加简单的多轮对话记忆（内存版）
- [ ] 记录每次调用的 token 消耗与预估成本

---

## 四、待优化 / 技术债

- [ ] `ChatService` 中 `RuntimeException` 可细化为自定义业务异常类型
- [ ] `GlobalExceptionHandler` 补充对超时、限流等异常的分类处理
- [ ] 敏感词/内容安全过滤（输入与输出双向）
- [ ] 补充单元测试（Service 层 mock ChatClient）

---

## 五、验收标准（Definition of Done）

- [ ] 应用可正常启动，无 Bean 装配错误
- [ ] 五大功能均可通过 curl 验证
- [ ] 异常路径（400 / 500）返回统一 Result 结构
- [ ] 完成思考题并能口述 Agent 核心公式

---

## 六、进入 Day2 前置条件

- [ ] 本模块 Demo 已跑通
- [ ] 理解 ChatClient Fluent API 调用链
- [ ] 掌握 System Prompt 与 Temperature 的作用