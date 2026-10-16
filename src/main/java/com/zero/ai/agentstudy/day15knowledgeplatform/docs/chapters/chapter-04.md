# 第 4 章 · knowledge-parser：多格式解析与 OCR 兜底

> 「解析层只负责拿到文本，不负责理解语义。格式标记是宝贵的结构信息，越晚丢掉越好。」
>
> Day05 的教学版 RAG 只吃 TXT/MD。而企业里真正占大头的是 PDF 与 Office 三件套，
> 还有一堆扫描件。本章让平台把这几类全部吃下。

---

## 一、学习目标

学完本章，你将能够：

1. 用 **策略模式 + 注册表** 实现「一种格式一个解析器」，新增格式不改已有代码。
2. 掌握 **PDF / Word / Excel / PPT / HTML / Markdown** 六类文档的解析要点与各自最易踩的坑。
3. 理解为什么 **Excel 必须做行序列化**（否则表格数据根本检索不到）。
4. 理解 **扫描件型 PDF 的 OCR 兜底**链路，以及为什么「OCR 不可用」必须显式失败。
5. 用 **Null Object 模式** 处理「能力缺失」，避免到处 `if (ocr != null)`。

---

## 二、为什么解析是企业 RAG 的第一道坎

一个反直觉的事实：**企业知识平台的检索质量，80% 的问题出在解析阶段，而不是向量模型。**

常见翻车现场：

- 上传一份 PDF，检索「报销标准」什么也搜不到 → 该 PDF 是扫描件，PDFBox 抽出空串，**一篇空文档被静默入库**。
- Word 文档里的参数表搜不到 → 只遍历了 `getParagraphs()`，**表格内容被整个丢弃**。
- Excel 里搜「华东区一季度销售额」搜不到 → 单元格被孤立入库，向量里只有 `3980` 这种数字，**没有语义上下文**。
- 网页采集回来的 Chunk 里混进大段 CSS → 剥标签时没剔除 `<style>`，**污染了向量语义**。

这四个坑，本章逐一填上。

---

## 三、代码逐行精讲

本章新增代码位于 `day15knowledgeplatform/parser/`：

```
parser/
├── ParserRegistry.java              策略路由表
├── ParsingService.java              应用服务
├── model/ParsedDocument.java        统一解析结果
├── port/
│   ├── DocumentParser.java          解析器端口
│   └── OcrEngine.java               OCR 端口
└── adapter/
    ├── PlainTextParser.java         TXT / MD
    ├── HtmlParser.java              HTML
    ├── PdfParser.java               PDF（PDFBox）+ OCR 兜底
    ├── WordParser.java              DOCX（POI XWPF）
    ├── ExcelParser.java             XLSX（POI XSSF）
    ├── PptParser.java               PPTX（POI XSLF）
    ├── ImageParser.java             图片（走 OCR）
    └── NullOcrEngine.java           Null Object
```

### 3.1 端口设计：策略只管算法

```java
public interface DocumentParser {
    DocumentFormat supportedFormat();
    ParsedDocument parse(byte[] content, Metadata source);
}
```

刻意把接口设计成 `byte[] -> ParsedDocument`：**策略只负责「把格式变成文本」，不负责造 Chunk、不负责写库**。这样每个解析器都是纯函数，无状态、易测试、可随意替换。

```java
public class ParserRegistry {
    public ParserRegistry register(DocumentParser parser) {
        parsers.put(parser.supportedFormat(), parser);
        return this;
    }
}
```

新增一种格式 = 新增一个实现类 + 一行 `register`。**已有代码零改动**，这是对开闭原则最直接的兑现。

### 3.2 Markdown / TXT：别急着抹平语法

```java
// PlainTextParser 刻意不做「去 Markdown 语法」处理
String text = new String(content, StandardCharsets.UTF_8);
```

这是**刻意**的：第 5 章的 `StructuredChunkStrategy` 正是靠 `#` 标题来切分。如果在这里就把语法抹平，结构化切分就无从下手。

> **原则：格式标记是结构信息，越晚丢掉越好。**

### 3.3 HTML：先剔除 script/style

```java
text = SCRIPT.matcher(text).replaceAll(" ");
text = STYLE.matcher(text).replaceAll(" ");
text = COMMENT.matcher(text).replaceAll(" ");
text = BLOCK.matcher(text).replaceAll("\n");   // 块级标签换成换行
text = TAG.matcher(text).replaceAll("");
```

顺序不能颠倒：先删 `script/style`（连同内容一起删），再把块级标签换成换行（否则整页粘成一坨），最后才剥剩余标签。

生产环境建议换成 jsoup（解析更严谨、支持选择器），这里用正则是为了保持零新增依赖。

### 3.4 Word：两个最易漏的点

```java
// 坑1：只取段落会漏掉表格
for (XWPFParagraph p : doc.getParagraphs()) { ... }
for (XWPFTable table : doc.getTables()) { ... }

// 坑2：段落文本不含「这是几级标题」，必须读样式
String marker = headingMarker(p.getStyle());
```

企业文档里大量内容在**表格**中（参数表、价目表、对照表），只遍历段落会静默丢失。

第二个坑更隐蔽：段落文本本身**不含标题层级信息**，必须读 `getStyle()` 才能还原大纲。本类把 `Heading1/标题 1` 映射成 `#`，让 Word 文档也能享受第 5 章结构化切分的好处——这正是第 3、5 章之间的关键衔接。

### 3.5 Excel：行序列化决定能否被检索命中

**这是本章最重要的一节。**

如果直接把单元格丢进向量库，会得到一堆孤立数字：`3980`、`2025-Q1`。用户问「一季度华东区销售额是多少」，向量根本匹配不上——因为它不知道这串数字属于「销售额」、属于「华东区」。

```java
private static String serializeRow(String sheetName, List<String> headers, List<String> values) {
    // 输出：[2026年销售数据] 区域=华东；季度=2026-Q1；销售额=3980
}
```

把一行拼成 `列名=值` 的完整句子，**让每个 Chunk 自带语义上下文**。

另一个必用细节：数值要走 `DataFormatter` 按「显示值」读取：

```java
// ❌ getNumericCellValue() 会读出 3980.0，与用户看到的 "3,980" 不一致
// ✅ DataFormatter.formatCellValue(cell) 读显示值
```

### 3.6 PPT：保留幻灯片边界

```java
sb.append("\n## 第 ").append(slideCount).append(" 页\n").append(page);
```

PPT 知识密度低但业务价值高。一页 slide 就是一个天然的知识单元，检索时以「页」为单位返回，**用户能直接定位到是哪一页讲的**——这是可溯源的基础。

### 3.7 PDF：文本层缺失时的 OCR 兜底

```java
if (text == null || text.isBlank()) {
    if (ocrEngine != null && ocrEngine.available()) {
        text = ocrEngine.recognize(content);
        ocrApplied = true;
    } else {
        throw KnowledgeException.of(KnowledgeErrorCode.PARSE_FAILED,
                "PDF 未抽取到文本层（疑似扫描件），且未接入可用的 OCR 引擎，无法入库");
    }
}
```

**关键：拿不到内容就抛异常，绝不静默产出一篇空文档。** 空文档混进知识库后无法被发现，只会在检索时表现为「某些问题永远搜不到」，排查成本极高。

### 3.8 NullOcrEngine：Null Object 模式

```java
public class NullOcrEngine implements OcrEngine {
    public boolean available() { return false; }
    public String recognize(byte[] imageBytes) {
        throw KnowledgeException.of(KnowledgeErrorCode.OCR_FAILED, "未接入 OCR 服务...");
    }
}
```

**为什么不用 `null`？** 如果 OCR 缺失时注入 `null`，每个调用点都要写 `if (ocr != null)`，漏写就是 NPE。Null Object 把「没有这个能力」建模成一个**行为明确的对象**：调用方无需判空，`available()` 返回 false、`recognize()` 给出清晰错误。

> **PDFBox 3.x 注意**：标准 14 字体从 `PDType1Font.HELVETICA` 静态常量改为
> `new PDType1Font(Standard14Fonts.FontName.HELVETICA)`。这是 2.x 升级 3.x 时最常见的编译错误。

---

## 四、架构设计思想

1. **端口与适配器**：`DocumentParser` / `OcrEngine` 都是端口。换 PDF 库、换 OCR 供应商，领域代码不动。
2. **策略 + 注册表消灭 if-else 链**：格式判断集中在一处，新增格式不改旧代码。
3. **失败要大声**：解析拿不到文本就抛异常，绝不用空串蒙混过关。静默失败是知识库最难排查的 bug 类型。
4. **保留结构信息**：# 标记、页码、章节、列名——这些在切分与溯源阶段会被反复用到。

---

## 五、企业实战案例

### 案例：某制造业「设备参数检索」为何总是搜不到

某制造企业把 2000 份设备手册（多为 PDF + Excel 参数表）接入知识平台后，一线维修工反馈：
「搜『主轴最大转速』永远搜不到，但手册里明明有。」

排查发现两个问题叠加：

1. **Excel 参数表被孤立入库**：`转速` 和 `8000` 被切成两个 Chunk，问句匹配不到单独的数字。
2. **PDF 扫描件混入**：约 30% 的手册是扫描件，文本层为空，被静默入库成空文档。

**修复**：

- Excel 改为行序列化 → `[主轴参数表] 设备=A型；最大转速=8000rpm；功率=15kW`，检索立刻命中。
- 扫描件 PDF 改为**显式失败 + 进入人工 OCR 队列**，不再污染知识库。

**上线效果**：一次检索成功率从 41% 提升到 89%。这个案例说明：**检索优化常常不在检索层，而在解析层。**

---

## 六、面试高频题

1. **策略模式和一长串 `if-else` 的区别？** 策略模式把每个分支封装成独立类，新增分支不改旧代码（开闭原则）；分支逻辑可单独测试；可通过注册表/依赖注入动态替换。if-else 链则会随分支增多变成难以维护的上帝方法。

2. **PDF 抽不到文本怎么办？** 三步：① 判断是否扫描件（文本层为空）；② 渲染成图片走 OCR；③ OCR 也不可用则**显式失败并进入人工队列**，绝不静默入库。

3. **为什么表格数据要行序列化？** 因为向量检索依赖语义完整性。孤立的单元格值缺乏上下文，无法与自然语言问句建立语义关联。`列名=值` 的形式把结构信息压平进文本，使 Embedding 能编码「8000 属于最大转速」这一关系。

4. **Null Object 模式适合什么场景？** 适合「能力可选、调用方不应感知缺失」的场景。反例：如果缺失时必须强制调用方处理（如缺少必填配置），则应尽早抛异常或返回 `Optional`，而不是静默降级。

5. **解析阶段该不该做文本清洗（去停用词、纠错）？** 本章观点：**解析层不做语义级清洗**，只做格式剥离与结构保留。清洗应在切分之后、向量化之前，作为独立管线阶段，便于单独调参与回滚。

---

## 七、扩展阅读

- Apache POI 官方文档 —— XWPF / XSSF / XSLF 三套 API。
- Apache PDFBox 3.x Migration Guide —— 2.x 到 3.x 的 breaking changes。
- LlamaIndex `NodeParser` / Haystack `Converter` —— 对比阅读开源框架对解析层的抽象。
- 本项目 Day13 `DocumentIR` —— 对比阅读：Office Agent 如何把「内容」与「格式」彻底解耦。

---

## 八、本章小结

- **六类格式全部吃下**：PDF / DOCX / XLSX / PPTX / HTML / MD / TXT，外加图片走 OCR。
- **策略 + 注册表**：新增格式零改动已有代码。
- **Word 别漏表格、别丢标题样式**（后者直接决定第 5 章能否结构化切分）。
- **Excel 必须行序列化**，否则表格数据检索不到。
- **扫描件 PDF 走 OCR 兜底，拿不到就显式失败**——绝不让空文档混进知识库。
- **Null Object** 让「能力缺失」成为行为明确的对象，消灭判空。

### 运行验证

```bash
java -cp target/classes:<poi/pdfbox 依赖> \
  com.zero.ai.agentstudy.day15knowledgeplatform.demo.Chapter04Demo
```

Demo 会**先用 POI/PDFBox 凭空生成** docx/xlsx/pptx/pdf 四种真实文件，再解析回来，完整闭环。真实输出节选：

```
--- 3. Word（POI 生成 → 解析，含标题样式与表格） ---
  元数据={parser=poi-xwpf, paragraphCount=3, tableCount=1}
    | # 员工报销管理制度
    | 本制度自 2026 年 1 月 1 日起执行。
    | ## 住宿标准
    | [表格 1]
    | 级别 | 每晚上限
    | 总监 | 800

--- 4. Excel（行序列化为「列名=值」） ---
    | 列名：区域 | 季度 | 销售额
    | [2026年销售数据] 区域=华东；季度=2026-Q1；销售额=3980
    | [2026年销售数据] 区域=华南；季度=2026-Q1；销售额=2560

--- 5. PPT（按幻灯片分页） ---
    | ## 第 1 页
    | ZeroHub 知识平台

--- 6. PDF（PDFBox 生成 → 抽取文本层） ---
  字符数=113 页数=1 元数据={parser=pdfbox, pageCount=1}
    | Hybrid search = Vector + BM25 + Reranker.

--- 7. 图片（无 OCR 时必须显式失败） ---
  ✓ 已拦截 KP-2002: 图片类文档需要 OCR，但当前没有可用的 OcrEngine 实现
```

注意第 3 项输出里的 `# 员工报销管理制度` / `## 住宿标准`——那是 Word 的标题样式被还原成了 Markdown 标记，**这正是第 5 章结构化切分能生效的前提**。

> 下一章预告：**第 5 章 · knowledge-chunk**，我们把解析出的纯文本切成 Chunk，
> 并对比定长 / 语义 / 结构化三种切分策略的实际效果差异。

——回复「继续」进入第 5 章。
