# 第 7 章 打通"最后一公里"：邮件、日历、任务与模板

## 7.1 为什么需要这一章

前六章我们把 AI Office Agent 的"生成"能力建得很扎实：从原始数据到结构化 `DocumentIR`（第 6 章），再到 Word/Excel/PPT/PDF 四种格式的字节产物（第 4、5 章）。但请回想我们的**终极场景**：

> "根据昨天销售数据生成一份周报，并制作 PPT，**发送给销售总监**，同时保存到知识库。"

到目前为止，我们只完成了"生成一份周报、制作 PPT"。剩下的动词——"**发送**"——我们还一个字都没写。一个生成了文档却送不出去的 Agent，就像一台印出报表却锁在保险柜里的打印机：技术上完成了，业务上等于零。

这就是本章要补齐的"**最后一公里**"。它由四个看似零散、实则同属"**产出的落地与流转**"的模块组成：

- **officemail**——把产物作为附件发出去（邮件分发）；
- **officecalendar**——把"什么时候做什么"沉淀成日历事件（`.ics`，可导入任何日历软件）；
- **officetask**——把一次 Agent 作业建模成**有生命周期的任务**，支撑挂起、审批、重试；
- **officetemplate**——把"半固定文档"用模板 + 占位符高效产出，而非每次都劳烦大模型。

这四个模块合起来，让 Agent 从"能造东西"升级为"能**把事办完**"。而 officetask 更是下一章 officeworkflow 全链路编排的地基——没有任务状态机，就谈不上"挂起等审批再恢复"。

## 7.2 邮件分发：officemail 与 MIME 多部件

### 7.2.1 为什么邮件仍是企业分发的"最大公约数"

在即时通讯如此发达的今天，为什么还要做邮件？因为在**跨组织、留痕、正式**这三个维度上，邮件至今没有替代品：发给客户的合同、发给总监的周报、发给全员的通知——它们需要一个带附件、可归档、有主题的正式载体。邮件就是企业协作的"最大公约数"。

### 7.2.2 端口契约：不抛异常，而是返回结果

我们把邮件能力抽象为出站端口 [`MailSender`](../../officecore/domain/port/MailSender.java:16)。它的方法签名有一个**刻意的设计**：

```java
SendResult send(MailMessage message);
```

注意它返回一个 `SendResult`，而**不是抛异常**。这是面向 Pipeline 的深思熟虑：在一条"生成→存储→分发"的流水线里，如果发信失败就抛异常打断整条链路，那么"文档已生成、已存储"这些**已经成功的成果**就白费了。让 `send` 返回 `SendResult(success, messageId, error)`，调用方可以决定"发信失败但任务仍算部分成功、记录告警后继续"，把**失败的处置权交给编排层**，而不是让底层适配器替业务做决定。

### 7.2.3 MIME 多部件：正文 + 附件

一封带附件的邮件在协议层是一棵 MIME 树：一个 `multipart/mixed` 容器，下挂"正文部件"和若干"附件部件"。[`SmtpMailSender`](../../officemail/adapter/SmtpMailSender.java:1) 用 Jakarta Mail 组装这棵树：

```java
MimeMessage message = new MimeMessage(session);
message.setFrom(new InternetAddress(from));
message.setSubject(subject, "UTF-8");

MimeMultipart multipart = new MimeMultipart();
// 正文部件
MimeBodyPart bodyPart = new MimeBodyPart();
bodyPart.setText(body, "UTF-8");
multipart.addBodyPart(bodyPart);
// 附件部件（周报 PPT / PDF）
MimeBodyPart attachmentPart = new MimeBodyPart();
attachmentPart.attachFile(...);
multipart.addBodyPart(attachmentPart);

message.setContent(multipart);
```

正是这一步，让第 5 章渲染出的 PPT `byte[]` 有了归宿——它作为一个 MIME 附件部件，飞到销售总监的收件箱。

### 7.2.4 SMTP 参数外置与匿名 Authenticator

SMTP 主机、端口、账号、密码这些**环境相关、且含敏感信息**的配置，绝不能硬编码。我们用 `@Value` 把它们从 `application.yml` 注入，密码交由**环境变量或密钥管理系统**提供。认证通过一个匿名 `Authenticator` 完成：

```java
Session session = Session.getInstance(props, new Authenticator() {
    @Override
    protected PasswordAuthentication getPasswordAuthentication() {
        return new PasswordAuthentication(username, password);
    }
});
```

> **避坑预告**：把密码写进 `application.yml` 再提交到 Git，是新手最常见、也最致命的一类事故。密码只能来自环境变量或密钥管理。

## 7.3 日历事件：officecalendar 与 RFC 5545

### 7.3.1 为什么要生成日历

Agent 办完事，往往还要"约定后续"：项目里程碑、复盘会议、跟进提醒。与其在正文里写一句"下周三下午 3 点开复盘会"（人还得手动录进日历），不如**直接产出一个 `.ics` 文件**，收件人双击即可加入自己的日历。`.ics` 是 iCalendar（RFC 5545）标准格式，Outlook、Google Calendar、Apple 日历全部原生支持。

### 7.3.2 关键决策：手工生成 RFC 5545 文本，而非绑定库 API

这是本章**第二条重要工程经验**（第一条见 7.2.2 的"返回结果而非抛异常"）。

我们的 `pom.xml` 里其实已经引入了成熟的日历库 `ical4j`（4.0.7）。但在实现 [`IcsCalendarAdapter`](../../officecalendar/adapter/IcsCalendarAdapter.java:1) 时，我们**没有使用它的对象模型 API**，而是选择手工拼接符合 RFC 5545 的纯文本。理由如下：

- **规范极其稳定**：iCalendar 规范三十年基本没变，`VCALENDAR`/`VEVENT` 的结构像 HTTP 报文一样朴素可靠；
- **库 API 版本脆弱**：`ical4j` 1.x/2.x/3.x/4.x 的构造器与属性 API 差异巨大——1.x 用 `getProperties().add(...)`、`DateTime` 等旧类型，4.x 完全重构。绑定库 API 意味着一次大版本升级就可能编译不过；
- **教学更透彻**：手工拼接让你**看清 `.ics` 到底长什么样**，而不是被库的抽象层挡住视线；
- **产物通用**：手工生成的文本任何日历软件都能打开，零运行时依赖。

这与第 6章"LLM 产简单 DTO"、以及后面 7.5 节"模板用纯占位符替换"是**同一种工程价值观**：**稳定规范优于易变库 API**。当一个格式/协议本身足够稳定时，直面它比引入一层多变的抽象更划算。当然，端口 [`CalendarPort`](../../officecore/domain/port/CalendarPort.java:1) 的契约不变，若团队重度依赖 ical4j，完全可以在适配器内部换成库实现，上层无感。

### 7.3.3 VCALENDAR / VEVENT 骨架

一个最小可用的 `.ics` 长这样（注意每行以 **CRLF** 结尾，这是 RFC 5545 §3.1 的硬性要求）：

```text
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//ZeroHub//Office Agent//CN
CALSCALE:GREGORIAN
METHOD:PUBLISH
BEGIN:VEVENT
UID:6f1e...@zerohub
DTSTAMP:20260730T090000Z
DTSTART:20260805T070000Z
DTEND:20260805T080000Z
SUMMARY:销售周报复盘会
DESCRIPTION:回顾本周销售数据与下周计划
LOCATION:三楼会议室
ORGANIZER:mailto:agent@zerohub.com
ATTENDEE:mailto:director@zerohub.com
END:VEVENT
END:VCALENDAR
```

三个要点决定了它能否被日历软件正确解析：

- **UTC 时间归一**：`DTSTART`/`DTEND` 统一转成 UTC 并带 `Z` 后缀，避免时区歧义。适配器用 `OffsetDateTime.withOffsetSameInstant(ZoneOffset.UTC)` 把任意时区的输入归一，再用 `DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")` 格式化；
- **UID 全局唯一**：每个事件必须有全局唯一的 `UID`，否则重复导入会被日历软件当作"更新同一事件"。适配器在 `UID` 为空时用 `UUID` + `@zerohub` 兜底；
- **文本值转义**：`SUMMARY`/`DESCRIPTION` 等文本值里的**反斜杠、分号、逗号、换行**必须按 RFC 5545 §3.3.11 转义（`\\`、`\;`、`\,`、`\n`），否则一个逗号就能撑破解析。

### 7.3.4 CalendarPort 端口与 byte[] 产物

日历产物同样序列化为 `byte[]`，与文档渲染器、邮件附件保持**统一的产物形态**——这样一个 `.ics` 既可以作为邮件附件飞出去，也可以存进 [`FileStorage`](../../officecore/domain/port/FileStorage.java:16)。端口方法设计为批量优先：

```java
byte[] renderIcs(List<CalendarEvent> events);          // 一个日历含多个事件
default byte[] renderIcs(CalendarEvent event) {         // 单事件便捷重载
    return renderIcs(List.of(event));
}
```

`CalendarEvent` 是内嵌 `record` 值对象，紧凑构造器做非空归一，并提供 `of(summary, start, end)` 快捷工厂——与项目里其它端口（`MailSender.MailMessage`、`OcrEngine.OcrResult`）的风格完全一致。

## 7.4 任务生命周期：officetask 与状态机

### 7.4.1 为什么一次作业要建模成"任务"

到这里，我们有了生成、有了分发。但一个真正的企业级 Agent 作业**不是"一把梭跑完"**：

- 它可能**耗时很长**（生成 + 渲染 + 发信可能要几十秒），需要一个 ID 让用户随时查进度；
- 它可能在**敏感动作前挂起**，等人工审批（"这封群发邮件真的要发吗？"）；
- 它可能**失败**，需要记录原因、支持重试；
- 它可能被**取消**。

这些诉求的共同点是：**作业有状态，且状态流转必须受控**。于是我们把一次作业建模成聚合根 [`OfficeTask`](../../officecore/domain/task/OfficeTask.java:22)，用 [`TaskStatus`](../../officecore/domain/task/TaskStatus.java:14) 枚举描述其生命周期状态机。

### 7.4.2 状态机：把业务规则收进领域模型

`TaskStatus` 不只是几个常量，它还带着**合法迁移规则**：

```java
public boolean canTransitionTo(TaskStatus target) {
    return switch (this) {
        case CREATED -> Set.of(RUNNING, CANCELLED).contains(target);
        case RUNNING -> Set.of(WAITING_APPROVAL, COMPLETED, FAILED, CANCELLED).contains(target);
        case WAITING_APPROVAL -> Set.of(RUNNING, CANCELLED, FAILED).contains(target);
        case COMPLETED, FAILED, CANCELLED -> false;   // 终态不可再迁移
    };
}
```

聚合根的每个状态变更方法（`start`/`awaitApproval`/`resume`/`complete`/`fail`/`cancel`）都经过私有 `transition()` 校验，非法跃迁直接抛 `IllegalStateException`。这样"已完成的任务又被改回运行中"这类错误**在领域层就被物理杜绝**，而不是靠 Service 里散落的 `if` 去防。这是 DDD"**富领域模型**"的精髓——业务规则收拢进聚合根，而非泄漏到各处。

### 7.4.3 仓储端口 + 薄应用层

任务需要持久化才能"进程重启不丢"。我们定义出站端口 [`TaskRepository`](../../officecore/domain/port/TaskRepository.java:23)，默认实现是零依赖的 [`InMemoryTaskRepository`](../../officetask/adapter/InMemoryTaskRepository.java:1)（用 `ConcurrentHashMap` 承载，便于教学与单测）；生产环境可另写 JPA/Redis 适配器替换，**零改动业务代码**。

应用服务 [`TaskService`](../../officetask/application/TaskService.java:1) 是"薄"的——它只做三件事：**取聚合根、调其方法、回写仓储**：

```java
public OfficeTask approve(String taskId) {
    OfficeTask task = require(taskId);   // 取
    task.resume();                       // 调（WAITING_APPROVAL → RUNNING，非法则抛异常）
    return taskRepository.save(task);    // 回写
}
```

> **务必记住**：`OfficeTask` 是内存可变对象，仓储可能是数据库。每次改完状态**必须 `save` 回写**，否则重启后丢最新状态——语义等同关系库的 "load → mutate → update"。

`requireApproval()` 与 `approve()` 这一对方法，正是下一章 Human-in-the-loop 审批的接口基础。

## 7.5 半固定文档：officetemplate 与占位符替换

### 7.5.1 不是所有文档都值得劳烦大模型

企业里大量文档是"**半固定**"的：周报抬头、放假通知、合同模板、邮件正文——骨架年年不变，变的只是当期数据（数字、日期、姓名）。让大模型每次从零生成，既慢、又贵、还不稳定（措辞飘、字段漏）。正确姿势是：**固定骨架用模板，可变部分用占位符，运行时把数据填进去**。

### 7.5.2 端口与零依赖适配器

我们定义 [`TemplateEngine`](../../officecore/domain/port/TemplateEngine.java:1) 端口，默认实现 [`SimpleTemplateEngine`](../../officetemplate/adapter/SimpleTemplateEngine.java:1) 用一个正则做 `${key}` 占位符替换，**零依赖、零版本风险**：

```java
private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{\\s*([A-Za-z0-9_.]+)\\s*}");

String rendered = engine.render("尊敬的${name}，本周销售额为${amount}元。",
        Map.of("name", "王总", "amount", 128000), false);
// → 尊敬的王总，本周销售额为128000元。
```

这又是"**稳定规范优于易变库 API**"的一次落地——占位符替换语义三十年不变，覆盖企业办公 80% 的半固定文档诉求。若确需循环、条件、嵌套对象，团队可另写 Freemarker 适配器替换，端口契约不变。

### 7.5.3 严格模式与"缺参体检"

`render` 支持 `strict` 参数：宽松模式下未知占位符**原样保留**（便于多阶段逐步填充），严格模式下**直接抛异常**。更进一步，`validate()` 能在渲染前做一次"缺参体检"，提前暴露漏填的字段：

```java
var result = engine.validate("金额：${amount}，日期：${date}", Map.of("amount", 100));
// result.valid() == false, result.missingKeys() == ["date"]
```

这把"成品文档里赫然出现一个 `${date}`"的尴尬，挡在了交付客户之前。`null` 值统一渲染为空串，避免文档里出现刺眼的 "null"。

## 7.6 四模块如何协同：回到终极场景

现在把四块拼起来，看它们如何服务终极场景：

1. **officetask** 建一个任务：`taskService.createTask(tenant, "生成销售周报并发给总监", [PPTX, PDF])`，状态 `CREATED → RUNNING`；
2. **officesummary + 渲染器**（前几章）生成周报 IR 并渲染成 PPT/PDF `byte[]`；
3. **officetemplate** 用模板渲染邮件正文：`"${director}您好，附件是${week}销售周报，请查收。"`；
4. **敏感动作前挂起**：`taskService.requireApproval(id)`，状态 `RUNNING → WAITING_APPROVAL`，等人工点头；
5. 审批通过：`taskService.approve(id)` 恢复 `RUNNING`；
6. **officemail** 把 PPT/PDF 作为 MIME 附件、模板文本作为正文发出，`SendResult` 回报结果；
7. **officecalendar** 生成一个"下周复盘会"的 `.ics`，一并作为附件或存入知识库；
8. `taskService.complete(id)`，状态 `RUNNING → COMPLETED`。

这条链路的**编排**，正是下一章 officeworkflow 要统一收口的事。本章我们已经把每一个"零件"打磨好并单独编译验证通过。

## 7.7 避坑清单

1. **SMTP 密码硬编码进配置并提交 Git**——最致命的一类事故。密码只能来自环境变量或密钥管理系统，配置文件里用占位符。
2. **发信失败就抛异常打断 Pipeline**——已生成、已存储的成果会白费。让 `send` 返回 `SendResult`，把失败处置权交给编排层。
3. **`.ics` 用 `\n` 而非 CRLF 换行**——RFC 5545 §3.1 强制 CRLF，部分严格的日历客户端会拒绝解析 LF 结尾的文件。
4. **`.ics` 时间不带时区/不归一 UTC**——收件人在不同时区看到的时间会错乱。统一 `withOffsetSameInstant(UTC)` 并加 `Z` 后缀。
5. **`VEVENT` 缺 `UID` 或 `UID` 不唯一**——重复导入会被当作"更新同一事件"而互相覆盖。为空时用 UUID 兜底。
6. **`SUMMARY`/`DESCRIPTION` 未按 §3.3.11 转义**——文本里一个逗号、分号或换行就能撑破解析。反斜杠、分号、逗号、换行都要转义。
7. **绑定日历/模板库的对象模型 API**——大版本升级 API 剧变导致编译不过。当规范本身稳定时，直面规范文本比引入多变抽象更稳。
8. **改完 `OfficeTask` 状态忘记 `save` 回写**——内存改了、库没改，重启即丢。每次状态变更后必须回写仓储。
9. **绕过状态机直接改状态**——把状态字段暴露出 setter 会让"已完成又变运行中"成为可能。只能通过聚合根方法 + `canTransitionTo` 校验流转。
10. **模板渲染不校验缺参**——成品文档里露出 `${amount}` 才被客户发现。交付前用 `validate()` 做缺参体检，或对正式产出用严格模式。
11. **模板替换值含 `$` 或 `\` 未转义**——正则替换会把它们当作分组引用。用 `Matcher.quoteReplacement(...)` 转义替换值。

## 7.8 小结

本章补齐了 Agent 的"最后一公里"：**邮件把产物送出去、日历把安排定下来、任务把作业管起来、模板把重复省下来**。四个模块看似零散，实则贯穿同一条主线——**产出的落地与流转**，并共享同一套工程价值观：端口与适配器解耦、产物统一为 `byte[]`、稳定规范优于易变库 API。

尤其是 officetask 的状态机与仓储，是下一章**全链路编排 + 人工审批**的直接地基。下一章，我们将用 officeworkflow 把前七章的所有零件——感知、规划、生成、渲染、分发、审批——串成一条可挂起、可恢复、可观测的完整流水线。

## 7.9 思考题

1. `MailSender.send` 返回 `SendResult` 而非抛异常，这个设计在"部分成功"的 Pipeline 里带来了什么好处？如果换成抛异常，编排层要多写哪些代码来抢救已有成果？
2. 我们为日历和模板都选择了"手工规范文本 / 纯占位符"而非引入库。请举一个**反例**：什么情况下引入 ical4j 或 Freemarker 反而更划算？端口设计如何保证这种切换零成本？
3. `TaskStatus.canTransitionTo` 把合法迁移写死在枚举里。如果未来要支持"失败后重试"（`FAILED → RUNNING`），需要改哪里？这种改动会不会破坏"终态不可迁移"的不变式，为什么？
4. `InMemoryTaskRepository` 用 `ConcurrentHashMap`。在虚拟线程大量并发的场景下，`OfficeTask` 作为可变聚合根被多线程同时修改会有什么风险？你会如何在不引入数据库的前提下加固它？
5. 终极场景第 4 步"敏感动作前挂起等审批"，如果审批一直不来，任务会永远停在 `WAITING_APPROVAL`。你会如何设计超时与自动取消？这会给状态机加哪条迁移？