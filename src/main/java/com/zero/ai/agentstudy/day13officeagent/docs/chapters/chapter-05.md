# 第五章 渲染适配器（二）：PPT 与 PDF

上一章我们完成了 Word 与 Excel 两个渲染器，它们都建立在 POI 提供的"高层文档模型"之上——POI 已经替我们抽象好了段落、表格、单元格。本章要挑战两种"抽象层次截然不同"的格式：

- **PPT（.pptx）**：抽象单位是"幻灯片（Slide）+ 形状（Shape）",空间是二维画布，没有"页面流"。
- **PDF**：几乎没有任何高层模型，一切都要我们**手动在画布上排版**——算坐标、判断换页、自己折行。

把这两种形态放进同一个 `DocumentRenderer` 端口，是对 IR 抽象合理性的又一次严苛检验。检验通过意味着：无论底层 API 多原始，上层永远只面对同一份 `DocumentIR`。

## 5.1 为什么 PPT 与 PDF 值得单独一章

回到终极场景——"根据销售数据生成周报，并制作 PPT，发送给销售总监"。这里 PPT 是**汇报载体**，PDF 是**归档与分发载体**（不可篡改、跨平台一致）。两者缺一不可，且它们暴露的工程难点，Word/Excel 完全没有：

| 难点 | PPT | PDF |
|------|-----|-----|
| 空间模型 | 二维画布，需手动定位形状 | 二维画布，需手动定位 + 换页 |
| 分页 | 按"标题切页" | 光标触底自动换页 |
| 文本折行 | 文本框自动处理 | **需自己按字宽折行** |
| 中文字体 | 系统字体可用 | **Standard14 不含 CJK，需降级/加载字体** |

## 5.2 PptRenderer：把线性 IR 切成幻灯片

[`PptRenderer`](../../officeppt/adapter/PptRenderer.java:50) 的 `format()` 返回 `DocumentFormat.PPT`，底层用 POI 的 XSLF 模块（`XMLSlideShow`）。

### 5.2.1 核心策略：以标题为界切页

Word 是"块流一路往下写",而 PPT 必须切成一张张幻灯片。我们采用最符合直觉的策略——**每遇到一个标题就新开一页**，标题作为页标题，其后的段落/列表作为该页正文，直到下一个标题：

```java
private void renderBlock(XMLSlideShow ppt, SlideCursor cursor, Block block) {
    switch (block) {
        case HeadingBlock h -> cursor.newSlideWithTitle(ppt, plainText(h.runs()));
        case ParagraphBlock p -> cursor.appendBullet(ppt, plainText(p.runs()), 0);
        case ListBlock l -> renderList(ppt, cursor, l);
        case TableBlock t -> renderTable(ppt, cursor, t);
        case ChartBlock c -> renderChart(ppt, cursor, c);
        case ImageBlock i -> renderImage(ppt, cursor, i);
        case PageBreakBlock pb -> cursor.forcePageBreak();
    }
}
```

### 5.2.2 SlideCursor：把分页复杂度收敛到一处

PPT 渲染的所有"脏活"——当前是哪张幻灯片、正文写到第几行、下一条正文的纵坐标——都被封装进内部类 [`SlideCursor`](../../officeppt/adapter/PptRenderer.java:214)。它对外只暴露三个语义化方法：

- `newSlideWithTitle(...)`：开新页 + 写页标题；
- `appendBullet(text, indent)`：向当前页正文追加一条带缩进的要点，自动下移纵坐标；
- `forcePageBreak()`：处理显式分页符。

这样 `renderXxx` 方法完全不必关心坐标计算，只表达"我要一个标题""我要一条正文"这样的语义意图。**把易错的坐标运算收敛到一个小类里,是手动排版类渲染器保持可维护的关键。**

### 5.2.3 表格与图片：PPT 上的一等公民

在 PPT 里，一张表格或一张图往往就是一页的主角，因此 `renderTable`/`renderImage` 都会先 `newSlideWithTitle` 独占一页。表格用 XSLF 的 `XSLFTable`，逐行 `addRow`、逐格 `addCell`，表头加粗、垂直居中：

```java
XSLFTable poiTable = slide.createTable();
XSLFTableRow headerRow = poiTable.addRow();
for (TableBlock.TableCell cell : table.header()) {
    fillCell(headerRow.addCell(), plainText(cell.runs()), true);
}
```

### 5.2.4 编译期踩的坑：坐标类型与枚举位置

写 PptRenderer 时编译器报了两个错，很有代表性：

1. `poiTable.setAnchor(new Rectangle(40, BODY_TOP, ptWidth-80, 40))` —— `Rectangle(int,int,int,int)` 不接受 `double`（`ptWidth` 是 double）。**修复**：改用 `Rectangle2D.Double`（浮点矩形）。
2. `cell.setVerticalAlignment(TableCell.VerticalAlignment.MIDDLE)` —— POI 3.x 里 `VerticalAlignment` 是 `org.apache.poi.sl.usermodel` 下的独立枚举，不是 `TableCell` 的内部类。**修复**：直接 `import ...VerticalAlignment` 使用。

> 经验：POI 各子模块（XWPF/XSSF/XSLF/SL）的枚举、坐标类型并不统一，跨模块复制代码时极易踩到"同名不同包"的坑，编译器是最好的老师。

## 5.3 PdfRenderer：从零手写一个极简排版引擎

[`PdfRenderer`](../../officepdf/adapter/PdfRenderer.java:48) 的 `format()` 返回 `DocumentFormat.PDF`，底层用 Apache PDFBox 3.x。与前三个渲染器最大的不同：**PDFBox 不提供段落、列表、表格这些高层模型**，它只给你一张画布和"在坐标 (x,y) 处写一段文字/画一张图"的原子操作。于是我们必须自己实现一个极简排版引擎。

### 5.3.1 RenderContext：光标 + 换页 + 折行三合一

排版的全部状态与操作被封装进内部类 [`RenderContext`](../../officepdf/adapter/PdfRenderer.java:203)，它维护：

- `cursorY`：当前光标的纵坐标（PDF 坐标系原点在**左下角**，越往下值越小）；
- `page` / `stream`：当前页与其内容流；
- 常规字体与加粗字体（Standard14 的 Helvetica）。

对外暴露的语义化方法：

- `newPage()`：关闭旧内容流、开新页、光标复位到顶部（`高度 - MARGIN`）；
- `ensureSpace(need)`：若剩余高度不足以放下 `need`，自动换页；
- `writeLine(text,size,bold)`：写一整行；
- `writeWrapped(text,size,bold)`：按可用宽度**自动折行**；
- `drawImage(img,w,h)`：在光标处画图。

### 5.3.2 折行算法：按字符宽度累加

PDF 不会替你折行，超出页宽的文字会直接画到纸外。`writeWrapped` 的做法是**逐字符累加、用字体度量计算当前行宽，一旦超过可用宽度就断行**：

```java
for (int i = 0; i < safe.length(); i++) {
    line.append(safe.charAt(i));
    float w = f.getStringWidth(line.toString()) / 1000 * size; // 字体单位→点
    if (w > maxWidth) {
        emit(line.substring(0, line.length() - 1), f, size);   // 写出上一行
        line = new StringBuilder().append(safe.charAt(i));      // 当前字符另起一行
    }
}
```

`getStringWidth` 返回的是"千分之一 em"单位，需除以 1000 再乘字号换算成点（pt）。`emit` 内部会 `ensureSpace` —— 因此折行与换页是自动联动的。

### 5.3.3 换页：光标触底自动翻页

每次 `emit` 前调用 `ensureSpace(LEADING)`：当 `cursorY - 行高 < MARGIN`（触到下边距）时就 `newPage()`。图片则用 `ensureSpace(h + 8)` 保证整张图不会被页边切断。这套"光标 + ensureSpace"模型，让任意长的文档都能正确分页，无需上层介入。

### 5.3.4 中文字体：Standard14 的硬伤与降级

这是 PDF 最大的坑：PDFBox 内置的 Standard14 字体（Helvetica 等）使用 **WinAnsi 编码，根本不包含中文字符**。若直接 `showText("销售周报")`，会抛 `IllegalArgumentException: U+9500 is not available in this font`，导致整篇渲染失败。

本章采用**可编码性降级**策略先保证不崩溃：

```java
private String sanitize(String text) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
        char c = text.charAt(i);
        sb.append(c < 128 ? c : '?');   // 非 ASCII 字符降级为 '?'
    }
    return sb.toString();
}
```

> **生产落地提示**：正式环境必须加载真正的 CJK 字体——把思源黑体等 TTF 放进资源目录，用 `PDType0Font.load(doc, inputStream)` 加载（Type0 支持 Unicode 与 CID），替换掉 Standard14。降级为 `?` 只是"宁可乱码不可崩溃"的兜底，绝不能作为最终方案交付。这也是为什么代码注释里明确写了"生产中应改为加载真实 CJK 字体"。

### 5.3.5 图片：解码 + 等比缩放 + 触底换页

`renderImage` 先用 `ImageIO.read` 把字节解码成 `BufferedImage`，再经 `LosslessFactory.createFromImage` 转成 `PDImageXObject`。若图片宽度超过页面可用宽度，按比例缩放；随后 `ensureSpace(h+8)` 保证整图不跨页，最后 `drawImage`。解码失败（`bimg == null`）时降级为一行提示文字，绝不让整篇挂掉。

## 5.4 常见误区

1. **误区：PDF 里直接 showText 中文。** → Standard14 不含 CJK，抛异常。必须降级或加载 CJK 字体（`PDType0Font`）。
2. **误区：忘记 `ensureSpace`，内容画到纸外。** → PDF 不自动换页，超出页面的内容"消失"。任何写操作前都要判断剩余空间。
3. **误区：PDFBox 坐标当成"原点在左上角"。** → PDF 原点在**左下角**，y 越大越靠上，换页要把 y 复位到"高度 - 边距"。
4. **误区：忘记关闭 `PDPageContentStream`。** → 换页/结束时未 `close()` 会导致内容不落盘或文件损坏。`RenderContext.close()` 统一处理。
5. **误区：PPT 跨模块复制 POI 代码不看包名。** → `VerticalAlignment` 在 XSLF/SL 下同名不同包，`Rectangle` 与 `Rectangle2D` 精度不同，编译期就会暴露。
6. **误区：把折行寄望于 PDFBox。** → 它不折行，必须自己按字宽度量断行。

## 5.5 小结与思考题

本章我们把"一份 IR、多格式输出"的能力补齐到**四种主流格式**：

- `PptRenderer` 以"标题切页"策略把线性 IR 投影成幻灯片，用 `SlideCursor` 收敛坐标复杂度。
- `PdfRenderer` 从零实现了一个含光标模型、自动换页、按字宽折行、中文降级的**极简排版引擎**。
- 两者都严格遵循同一个 `DocumentRenderer` 端口、隔离各自底层库依赖、对 Block 做穷尽 switch。
- 越是底层原始的格式（PDF），越能凸显 IR 抽象的价值：上层永远只面对同一份 `DocumentIR`。

**思考题：**

1. 为什么说 PDF 渲染器"顺带实现了一个排版引擎"，而 Word 渲染器不需要？这背后是两个库抽象层次的差异，你能否举出更多例子？
2. `PptRenderer` 的 `SlideCursor` 与 `PdfRenderer` 的 `RenderContext` 都在"收敛坐标复杂度"。它们能否抽象出一个公共基类？值得吗？
3. 中文降级为 `?` 是兜底方案。请描述用 `PDType0Font` 加载思源黑体的完整步骤，以及字体文件应如何随应用打包分发。
4. 四个渲染器（Word/Excel/PPT/PDF）都实现了同一个端口。如果要"并行生成四种格式",你会怎么用 Java 21 的虚拟线程实现？IR 的不可变性在这里起了什么作用？

下一章，我们将进入 `officesummary` 模块，用 Spring AI 2 的结构化输出能力，让大模型直接产出 `DocumentIR`——把"读数据 → 分析 → 生成文档结构"这条链路真正打通。