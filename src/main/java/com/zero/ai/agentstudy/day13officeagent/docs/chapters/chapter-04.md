# 第四章 渲染适配器（一）：把 IR 变成 Word 与 Excel

> 上一章我们把"文档是什么"抽象成了与格式无关的 `DocumentIR` 语义树。本章开始动真格——引入 Apache POI，编写第一批渲染适配器 [`WordRenderer`](../../officeword/adapter/WordRenderer.java:50) 与 [`ExcelRenderer`](../../officeexcel/adapter/ExcelRenderer.java:43)，把同一棵 IR 树真正落成可用 Office 打开的 .docx 与 .xlsx 文件。

## 4.1 为什么先做 Word 和 Excel

在企业办公场景里，Word 和 Excel 是使用频率最高、也最能验证 IR 设计是否合理的两种格式：

- **Word（.docx）是"流式文档"**：内容自上而下顺序排布，标题、段落、表格、列表、图片依次流动——它最能检验我们的 `Block` 列表模型对不对。
- **Excel（.xlsx）是"二维网格"**：以行列为一等公民——它逼我们思考"当同一份 IR 被投影到完全不同的载体上时，如何取舍"。

用这两种"形态差异极大"的格式做首批适配器，能一次性暴露 IR 抽象里所有不合理的地方。事实上，本章确实暴露并修复了一个 IR 设计缺陷（见 4.5）。

## 4.2 引入 Apache POI 依赖

渲染 Office 文档的事实标准是 [Apache POI](https://poi.apache.org/)。我们在 [`pom.xml`](../../../../../../../../pom.xml) 中新增一批 Day13 相关依赖（一次性把后续章节要用的也加好，避免反复改动）：

```xml
<!-- Apache POI：Word(.docx) / Excel(.xlsx) / PowerPoint(.pptx) 渲染 -->
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-ooxml</artifactId>
    <version>5.3.0</version>
</dependency>
```

`poi-ooxml` 一个依赖同时提供了 XWPF（Word）、XSSF（Excel）、XSLF（PPT）三套 API，是渲染 OOXML 格式的统一入口。

> **POI 术语速记**：`XWPF*` = Word，`XSSF*` = Excel，`XSLF*` = PowerPoint，`HWPF/HSSF` 是旧的二进制 .doc/.xls（本教程不用）。

## 4.3 WordRenderer：把 IR 翻译成 .docx

### 4.3.1 它在架构中的位置

[`WordRenderer`](../../officeword/adapter/WordRenderer.java:50) 位于 `officeword/adapter/`——六边形架构的**适配器层**。它实现领域定义的出站端口 [`DocumentRenderer`](../../officecore/domain/port/DocumentRenderer.java:19)：

```java
@Component
public class WordRenderer implements DocumentRenderer {
    @Override public DocumentFormat format() { return DocumentFormat.WORD; }
    @Override public byte[] render(DocumentIR ir) { ... }
}
```

**关键设计**：所有对 Apache POI 的 `import`（`XWPFDocument` 等）都被牢牢关在这个类里。领域内核（`officecore`）完全不知道 POI 的存在——它只认识 `DocumentIR` 和 `DocumentRenderer` 接口。未来若要把 Word 引擎换成 docx4j，只需新增一个实现类，Pipeline 和领域代码一行都不用改。这就是**依赖倒置**带来的可替换性。

### 4.3.2 渲染主流程：try-with-resources + 穷尽 switch

```java
public byte[] render(DocumentIR ir) {
    try (XWPFDocument doc = new XWPFDocument();
         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        applyMetadata(doc, ir.metadata());
        for (Block block : ir.blocks()) {
            renderBlock(doc, block);
        }
        doc.write(out);
        return out.toByteArray();
    } catch (IOException e) {
        throw new UncheckedIOException("渲染 Word 文档失败", e);
    }
}
```

三个工程细节：

1. **`try-with-resources`** 确保 `XWPFDocument` 与流一定被关闭，杜绝 POI 常见的句柄泄漏。
2. **渲染成字节数组而非直接写文件**——渲染器只负责"生成内容"，"存到哪里"是 `FileStorage` 端口的职责。职责分离让产物可以灵活地存本地、传 MinIO、当邮件附件或走 HTTP 下载。
3. **`IOException` 包装成 `UncheckedIOException`**——领域端口签名不抛受检异常，保持接口整洁。

### 4.3.3 renderBlock：sealed 接口的穷尽分派

整个渲染器的核心是对 `Block` 的 `switch` 模式匹配：

```java
private void renderBlock(XWPFDocument doc, Block block) {
    switch (block) {
        case HeadingBlock h   -> renderHeading(doc, h);
        case ParagraphBlock p -> renderParagraph(doc, p);
        case TableBlock t     -> renderTable(doc, t);
        case ListBlock l      -> renderList(doc, l);
        case ChartBlock c     -> renderChartAsTable(doc, c);
        case ImageBlock i     -> renderImage(doc, i);
        case PageBreakBlock pb -> renderPageBreak(doc, pb);
    }
}
```

注意：**没有 `default` 分支**。因为 `Block` 是 sealed 接口，编译器已知其全部七种实现，能做穷尽性检查。这带来一个巨大的工程收益——将来 IR 层新增一种块（比如 `FootnoteBlock`），这个 `switch` 会立即编译报错，强制你补齐 Word 渲染逻辑。**漏渲染从"线上文档缺内容"的事故，被提前成了编译失败。**

### 4.3.4 各类块的 Word 映射

| IR 块 | POI 映射策略 | 要点 |
|---|---|---|
| `HeadingBlock` | `p.setStyle("Heading" + level)` | 复用 Word 内置标题样式，自动进目录；字号按级别递减 |
| `ParagraphBlock` | `XWPFParagraph` + 逐 Run 写入 | 段落对齐用 `toWordAlignment` 映射；每个 Run 独立套样式 |
| `TableBlock` | `doc.createTable()` | 表头加粗；数据行 `createRow`；空缺单元格 `addNewTableCell` 兜底 |
| `ListBlock` | 段落 + 缩进 + 前缀符号 | 有序用递增数字，无序用 `•`，按 `indent` 设 `setIndentationLeft` |
| `ImageBlock` | `run.addPicture()` | 内联字节流写入，按像素尺寸转 EMU；失败降级为占位文字 |
| `PageBreakBlock` | `p.setPageBreak(true)` | 强制分页 |
| `ChartBlock` | 暂降级为标题占位 | Word 原生图表在 ch05 深化，先保证数据不丢 |

富文本样式的翻译集中在 `writeStyledRun`——把 IR 里格式无关的 `TextStyle`（加粗/斜体/下划线/字号/颜色）逐一翻译成 POI 的 `XWPFRun` API。这正是"抽象样式 → 具体 API"的翻译层。

## 4.4 ExcelRenderer：同一份 IR，投影到二维网格

[`ExcelRenderer`](../../officeexcel/adapter/ExcelRenderer.java:43) 同样实现 `DocumentRenderer`，但 `format()` 返回 `DocumentFormat.EXCEL`。它和 Word 渲染器共享同一个输入（`DocumentIR`）、同一个接口，却给出完全不同的渲染策略——这正是"一份 IR、多格式输出"的价值最直观的体现。

###4.4.1 核心矛盾：流式文档 vs 二维网格

Word 是线性的，IR 的块列表可以一一对应地"流"下去。但 Excel 的本质是"表格 + 工作表"，硬把一段正文塞进单元格既不自然也不好用。因此 ExcelRenderer 采用**分而治之**的投影策略：

- **表格类内容（`TableBlock`、`ChartBlock` 数据）是一等公民**：每个 `TableBlock` 独占一张工作表（Sheet），直接落成行列；`ChartBlock` 的类目/系列落成一张"数据表"（首列类目、每列一个系列）。
- **叙述类内容（标题、段落、列表）降级为文本行**：统一写进名为"正文"的首张工作表，保证信息不丢失，只是不再是主角。

```java
Sheet narrative = wb.createSheet("正文");
int[] narrativeRow = {0};
int[] tableIndex = {1};
for (Block block : ir.blocks()) {
    renderBlock(wb, narrative, narrativeRow, tableIndex, block, headerStyle);
}
```

> 这里用 `int[]` 单元素数组充当"可变计数器"，是在 lambda/循环里传递可变行号的常见 Java 技巧（避免为一个计数器专门建类）。

### 4.4.2 Excel 特有的工程细节

1. **数值智能识别**（`setCellValue`）：把形如 `"1,234"`、`"32%"` 的字符串尝试解析成数字写入数字单元格，失败才退回文本。这让生成的 Excel 里的数据**可以直接参与公式计算与排序**，而不是一堆"文本形态的数字"。
2. **工作表名合法化**（`sanitizeSheetName`）：Excel 工作表名不得包含 `: \ / ? * [ ]`，且长度不得超过 31 字符——这是 POI 最常见的运行时异常来源，必须提前清洗。
3. **工作表名唯一化**（`uniqueName`）：多个同名表格（比如两张都叫"销售明细"）会导致 POI 抛异常，用后缀 `_1/_2` 保证唯一。
4. **`autoSizeColumn`** 自动列宽，让生成的表格开箱即用、无需手动拉列宽。

### 4.4.3 穷尽 switch 的第二次红利

ExcelRenderer 的 `renderBlock` 同样是对 `Block` 的无 `default` 穷尽 `switch`。注意 `PageBreakBlock` 分支被显式写成空实现（`{ }`）并注释"Excel 无分页符语义，忽略"——这不是偷懒，而是**主动声明"我认真考虑过这种块，决定忽略它"**。有了 sealed + 穷尽检查，任何块都不可能被"悄悄漏掉"，每种块要么被渲染、要么被显式忽略，一切尽在编译器掌控。

## 4.5 踩坑实录：一个被渲染器暴露出来的 IR 设计缺陷

编写渲染器时，我们把 `TextStyle` 的派生方法命名成了 `bold()`、`italic()`、`underline()`：

```java
public record TextStyle(boolean bold, boolean italic, ...) {
    public TextStyle bold() { ... }   // ❌ 与 record 访问器 bold() 同名冲突！
}
```

编译直接失败：

```
记录 TextStyle 中的存取方法无效
（存取方法 bold() 的返回类型必须与记录组件 bold 的类型相匹配）
```

**根因**：Java `record` 会为每个组件自动生成一个同名访问器 `boolean bold()`。我们又手写了一个返回 `TextStyle` 的 `bold()`，两者签名冲突——record 组件访问器的返回类型被强制要求等于组件类型（`boolean`），编译器直接拒绝。

**修复**：把派生方法改名为 `withBold()` / `withItalic()` / `withUnderline()`，与访问器区分开。这也更符合不可变对象"写时复制"方法的惯用命名（`withXxx`）。

> **经验教训**：`record` 的组件访问器名是"保留"的，任何自定义方法都不能与之同名而返回不同类型。给不可变对象的派生方法统一加 `with` 前缀，既能规避冲突，也让"这是一次写时复制"的语义一目了然。这个坑是被真实的编译过程逼出来的——**多格式渲染器是 IR 抽象最好的试金石**。

## 4.6 常见误区

1. **误区：渲染器直接把文件写到磁盘。** → 违反职责单一，且无法适配邮件附件/HTTP 下载。渲染器只产 `byte[]`，落地交给 `FileStorage`。
2. **误区：忘记关闭 `XWPFDocument`/`Workbook`。** → POI 句柄泄漏。务必用 try-with-resources。
3. **误区：Excel 里数字都写成字符串。** → 无法计算求和。要做数值识别，写进数字单元格。
4. **误区：工作表名直接用用户标题。** → 含非法字符或超 31 字符时 POI 抛异常。必须清洗 + 唯一化。
5. **误区：`switch` 里加 `default` 图省事。** → 会让 sealed 的穷尽检查失效，新增块类型时不再编译报错。宁可显式写空分支。
6. **误区：给 record 组件写同名派生方法。** → 编译冲突（见 4.5）。用 `withXxx` 前缀。

## 4.7 小结与思考题

本章我们完成了首批渲染适配器：

- `WordRenderer` 把 IR 的七种块流式翻译成 .docx，充分利用 sealed switch 的穷尽安全。
- `ExcelRenderer` 用"表格一等公民、叙述降级"的投影策略把同一份 IR 落成 .xlsx，并处理了数值识别、工作表命名等 Excel 特有坑点。
- 两个渲染器共享 `DocumentRenderer` 端口、隔离各自的 POI 依赖，践行依赖倒置与可替换性。
- 顺带被编译器逼着修复了 `TextStyle` 的一处 record 命名缺陷。

**思考题：**

1. 为什么 `WordRenderer` 和 `ExcelRenderer` 面对同一份 IR 能产出结构差异巨大的文档？这说明 IR 抽象在哪个维度上做对了？
2. `renderBlock` 里的 `switch` 不写 `default`，好处是什么？如果写了 `default` 会失去什么？
3. Excel 渲染时把段落"降级"为文本行，是否损失了信息？如果业务要求段落也保留富文本样式，你会怎么改进？
4. 假如要新增一个 `HtmlRenderer`，你需要改动 `officecore` 领域层的任何代码吗？为什么？

下一章，我们将实现 `PptRenderer`（POI XSLF）与 `PdfRenderer`（PDFBox），把"一份 IR 多格式输出"的能力补齐到四种主流格式，并引入 Virtual Threads 并行渲染。