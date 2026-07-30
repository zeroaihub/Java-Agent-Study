# Day13 · AI Office Agent 任务清单（TODO）

> 三级难度：⭐ 必做（掌握核心）｜⭐⭐ 进阶（工程能力）｜⭐⭐⭐ 企业挑战（可商业化）。
> 建议按顺序推进，每完成一档再进入下一档。

---

## ⭐ 必做（Must Have · 打通核心链路）

- [ ] **搭建 officecore 领域核心**：定义统一的 Document IR（文档中间表示）、OfficeTask 聚合、OfficeContext 办公上下文。
- [ ] **定义领域端口**：`DocumentRenderer` / `MailSender` / `FileStorage` / `KnowledgeStore` / `OcrEngine` / `ModelPort`。
- [ ] **Word 文档生成**：基于 Apache POI，把 Document IR 渲染为 `.docx`（标题/段落/表格）。
- [ ] **Excel 自动统计**：读取 Excel、按维度聚合（求和/平均/分组），产出统计结果。
- [ ] **Markdown 输出**：把 IR 渲染为 Markdown，作为最轻量的交付格式。
- [ ] **自动生成会议纪要**：输入会议文本 → LLM 抽取议题/结论/待办 → 结构化纪要。
- [ ] **REST API**：`POST /api/office/agent/execute`，接收自然语言指令，返回任务结果。
- [ ] **一条最小 Pipeline 跑通**：指令 → 规划 → 生成 IR → 渲染 Word → 返回文件。

---

## ⭐⭐ 进阶（Advanced · 补齐工程能力）

- [ ] **PPT 自动生成**：Document IR → POI XSLF 渲染多页 `.pptx`（封面/目录/内容页）。
- [ ] **PDF 总结**：读取长 PDF → 分段摘要 → 汇总 → 导出总结 PDF。
- [ ] **自动生成日报**：聚合当日待办/提交记录 → LLM 组织 → 日报文档。
- [ ] **自动生成周报**：聚合一周数据 + 昨日销售 Excel → 周报（Word + PPT）。
- [ ] **邮件发送**：JavaMail/SMTP 发送带附件邮件，支持 HTML 正文。
- [ ] **日历事件**：生成 iCal 事件（会议/提醒），可导入 Outlook/Google Calendar。
- [ ] **ToDo 待办管理**：从纪要中抽取待办 → 落库 → 跟进状态。
- [ ] **模板引擎**：`.docx/.pptx` 占位符模板 + 数据模型合并，降低 Token 成本。
- [ ] **OCR 集成**：图片/扫描件 → 文本，接入感知阶段。
- [ ] **Browser 联动**：复用 Day9 Playwright，下载数据源文件 / 上传产出文件。
- [ ] **Human Approval**：对外发送前插入审批闸门（复用 Day11），支持通过/拒绝/超时。
- [ ] **可观测性**：为每个 Pipeline 阶段加 Micrometer 指标 + OpenTelemetry Trace + 审计日志。
- [ ] **虚拟线程 + 结构化并发**：并行生成 Word/PPT/PDF，任一失败整体回滚。

---

## ⭐⭐⭐ 企业挑战（Enterprise · 可商业化）

- [ ] **企业办公助手**：一句话完成"读 Excel → 分析 → Word → PPT → PDF → 邮件 → 上传 → 知识库 → 审批"全链路（本课程终极目标）。
- [ ] **企业秘书 Agent**：主动型 Agent——每日定时汇总日程、待办、邮件，生成"今日简报"并推送。
- [ ] **自动审批办公流**：把请假/报销/合同等审批流建模为可配置 Workflow，Agent 预填 + 人工审批 + 归档。
- [ ] **合同审核 Agent**：合同 PDF → 关键条款抽取 → 风险标注 → 生成审核意见 → 审批流。
- [ ] **知识库自动整理**：批量文档 → 分类/去重/摘要 → 结构化入库（pgvector），支持语义检索。
- [ ] **文件智能整理**：扫描目录 → 按内容分类重命名归档 → 生成整理报告。
- [ ] **多租户 SaaS 化**：租户隔离（数据/资源/计量/配置）、按用量计费、配额限流。
- [ ] **私有化交付包**：Docker + K8s + Helm，替换内网模型/SMTP/存储即可交付政企。
- [ ] **集成 ZeroHub**：把 AI Office Agent 作为一个 Agent 能力注册到 ZeroHub AI Agent Platform。

---

## 验收标准（Definition of Done）

一个模块视为"企业级完成"，需同时满足：

1. **可运行**：`mvn spring-boot:run` 启动后，对应 API 可通过 curl 验证。
2. **可测试**：核心领域逻辑有 JUnit5 单元测试；集成路径有 Testcontainers 测试。
3. **可观测**：关键动作有指标与日志，失败有明确错误码。
4. **可扩展**：新增格式/新增交付渠道无需修改 officecore。
5. **无 Demo 味**：无硬编码密钥、有异常处理、有租户上下文、有审计。

---

## 学习路径建议

```
第1天上午  ⭐ officecore + Word + Markdown + 会议纪要 + 最小 Pipeline
第1天下午  ⭐⭐ Excel/PPT/PDF + 日报/周报 + 模板引擎
第2天上午  ⭐⭐ 邮件/日历/待办 + OCR + Browser + Human Approval + 可观测
第2天下午  ⭐⭐⭐ 终极链路 + 秘书 Agent + SaaS 化 + 集成 ZeroHub
```

> 完成本清单，你将拥有一个可开源、可商业化、可用于真实企业项目的 AI Office Agent。