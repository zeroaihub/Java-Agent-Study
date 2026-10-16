# 第九章 API 网关与管理后台：把能力对外开放、把运维握在手里

> 本章配套模块：`wechatapi` / `wechatadmin`
> 关键词：REST API、入站适配器、统一响应、全局异常、参数校验、DTO/领域分离、Webhook 接入、管理后台、死信运维

---

## 一、为什么学：前八章造好了发动机，现在要装方向盘和仪表盘

前八章我们造好了一台强劲的引擎：消息、会话、记忆、路由、命令、RAG、Workflow、通知中心。可这台引擎目前只能「在代码里被调用」——外部系统够不着，运营也看不见。

企业级平台还差两样东西：

- **方向盘（对外 API）**：让企业微信回调、第三方系统、前端页面能通过标准 HTTP 接口驱动平台能力。这是 `wechatapi` 模块。
- **仪表盘（管理后台）**：让运维/运营能看到平台健康度、处理异常（尤其是**死信队列**这种「需要人来兜底」的数据）。这是 `wechatadmin` 模块。

这正是六边形架构里的**入站适配器（Driving Adapter）**——外部世界驱动应用的入口。回顾一下六边形架构的全景：

```
        入站适配器 (Driving)                    出站适配器 (Driven)
     +----------------------+              +------------------------+
     | REST Controller      |              | InMemoryRepository     |
     | Webhook Controller   | --> 应用层 --> | WechatWorkNotifySender |
     | Admin Controller     |   领域层      | EmailNotifySender      |
     +----------------------+              +------------------------+
          (本章)                                 (前几章)
```

前几章我们一直在写**领域层 + 出站适配器**，本章补齐**入站适配器**，六边形终于闭环。

> 一句话总结：**API 让平台能被外部驱动，管理后台让平台能被人运维。本章把「能力」变成「可被调用、可被运维的服务」。**

本章的接口也直接支撑第十章终极场景——会议纪要跑完后，正是通过 `/api/notifications` 发送，通过 `/admin/dead-letters` 兜底失败。

---

## 二、是什么：入站适配器的六个核心构件

### 2.1 统一响应 ApiResponse —— 所有接口长一个样

企业级 API 第一条铁律：**所有接口返回同一种外层结构**。调用方只写一套解析逻辑：

```java
public record ApiResponse<T>(int code, String message, T data, Instant timestamp) {
    public static <T> ApiResponse<T> ok(T data)          { ... }   // code=0 成功
    public static <T> ApiResponse<T> fail(int code, String message) { ... }  // 非0 业务错误
}
```

关键设计：**业务码独立于 HTTP 状态码**。

| 维度 | HTTP 状态码 | 业务码（code） |
| --- | --- | --- |
| 表达什么 | 传输/协议层结果 | 业务语义结果 |
| 例子 | 200/404/500 | 0成功 / 400参数错 / 1业务拒绝 |
| 谁关心 | 网关、负载均衡、监控 | 业务调用方 |

二者分离，调用方能精准区分「请求没打通（HTTP 500）」和「请求通了但业务拒绝（HTTP 200 + code≠0）」。

### 2.2 DTO 与领域模型分离 —— 协议层不污染领域层

请求体 `SendNotificationRequest` 是**协议层 DTO**，只关心传参与校验；`Notification` 是**领域模型**，只关心业务不变量。Controller 负责翻译：

```
HTTP JSON --> SendNotificationRequest(DTO) --[Controller翻译]--> Notification(领域) --> NotificationCenter
```

> **为什么不直接用领域对象接收请求？** 因为协议会变（加字段、改校验），领域不该跟着变；而且领域对象有不变量约束（构造即合法），直接暴露给外部反序列化会破坏封装。DTO 是一道**隔离带**。

### 2.3 声明式参数校验 —— 把校验从代码里挪到注解上

用 Bean Validation 注解声明约束，配合 `@Valid` 自动触发：

```java
public record SendNotificationRequest(
    @NotBlank(message = "租户 ID 不能为空") String tenantId,
    @NotBlank(message = "群 ID 不能为空")   String groupId,
    @NotBlank @Size(max = 128) String title,
    @NotBlank String markdown,
    String dedupeKey) {}
```

校验失败时 Spring 抛 `MethodArgumentNotValidException`，由全局异常处理器统一转 400——**Controller 里一行 if 校验都不用写**。

### 2.4 全局异常处理 GlobalExceptionHandler —— 绝不把裸异常栈抛给调用方

`@RestControllerAdvice` 统一拦截所有 Controller 异常，分三类处理：

| 异常类型 | HTTP | 处理 |
| --- | --- | --- |
| `MethodArgumentNotValidException` | 400 | 返回字段级校验错误 |
| `IllegalArgumentException` | 400 | 返回业务提示（如「通知不存在」） |
| 兜底 `Exception` | 500 | 通用提示，**不泄露堆栈**，服务端日志完整记录 |

> **安全要点**：裸异常栈会暴露内部类名、SQL、文件路径，是信息泄露漏洞。对外只给「服务器内部错误，请稍后重试」，细节留在服务端日志。

### 2.5 REST Controller —— 只翻译不写业务

`NotificationController` 三个接口全是「翻译 + 编排」，零业务逻辑：

- `POST /api/notifications`：DTO → 领域 → 调 `NotificationCenter.send` → 领域 → View。
- `GET /api/notifications/dead-letters/{tenantId}`：查死信 → 转 View 列表。
- `POST /api/notifications/{id}/redeliver`：调 `redeliver` → View。

出站也用 `NotificationView` 视图 DTO，**只暴露调用方需要的字段**，隐藏领域内部结构（如内部状态迁移方法）。

### 2.6 管理后台 wechatadmin —— 运维视角，独立模块

后台接口挂 `/admin/**`，与对外 `/api/**` 区隔，便于在网关层对整段路径施加**管理员鉴权**。核心是**死信运维**：

- `GET /admin/dead-letters/{tenantId}`：死信看板（数量 + 明细）。
- `POST /admin/dead-letters/{id}/redeliver`：管理员人工重投。

> **为什么 admin 独立成模块？** 后台的权限模型、审计要求、数据视角都与对外 API 不同，独立模块便于施加不同安全策略，也符合 Spring Modulith「按业务能力划分模块」的思想。

---

## 三、怎么用：逐段代码解析

### 3.1 ApiResponse：泛型 record + 静态工厂

```java
public record ApiResponse<T>(int code, String message, T data, Instant timestamp) {

    public static final int CODE_SUCCESS = 0;       // 成功
    public static final int CODE_BIZ_ERROR = 1;     // 通用业务失败
    public static final int CODE_INVALID_PARAM = 400; // 参数非法

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(CODE_SUCCESS, "success", data, Instant.now());
    }

    public static <T> ApiResponse<T> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null, Instant.now());
    }
}
```

要点：

- **泛型 record**：`T` 让 `data` 类型安全，成功携带业务数据，失败 `data=null`。
- **静态工厂而非 new**：调用点读起来是 `ApiResponse.ok(view)`、`ApiResponse.fail(400, "参数错")`，语义自解释。
- **常量业务码集中定义**：避免魔法数字散落各处，也方便调用方对照。
- `timestamp` 用 `Instant.now()`：便于调用方排查、幂等去重、时钟对齐。

### 3.2 GlobalExceptionHandler：三类分级拦截

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 1. Bean Validation 校验失败 -> 400 + 首个字段错误 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse("参数校验失败");
        return ApiResponse.fail(ApiResponse.CODE_INVALID_PARAM, msg);
    }

    /** 2. 业务参数非法（领域层主动抛出）-> 400 */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException ex) {
        return ApiResponse.fail(ApiResponse.CODE_INVALID_PARAM, ex.getMessage());
    }

    /** 3. 兜底：未预料异常 -> 500，不泄露堆栈，服务端记录 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleUnknown(Exception ex) {
        log.error("未处理的服务器异常", ex); // 完整堆栈留在服务端日志
        return ApiResponse.fail(ApiResponse.CODE_BIZ_ERROR, "服务器内部错误，请稍后重试");
    }
}
```

要点：

- **分级顺序**：越具体的异常越靠前，最后用 `Exception` 兜底，保证任何漏网异常都被转成规范响应。
- **`@ResponseStatus`**：同时设置 HTTP 状态码，让网关/监控能按 HTTP 码统计错误率。
- **对外脱敏、对内详尽**：`log.error(..., ex)` 记录完整堆栈供排查，返回体只给一句安全提示。

### 3.3 SendNotificationRequest：声明式校验 DTO

```java
public record SendNotificationRequest(
        @NotBlank(message = "租户 ID 不能为空") String tenantId,
        @NotBlank(message = "群 ID 不能为空")   String groupId,
        @NotBlank(message = "标题不能为空")
        @Size(max = 128, message = "标题不能超过 128 字符") String title,
        @NotBlank(message = "内容不能为空") String markdown,
        String dedupeKey /* 可空：传了才启用幂等去重 */) {
}
```

要点：`dedupeKey` **不加 @NotBlank**——它是可选的幂等键，传了才走去重分支。这体现「DTO 的校验规则完全贴合协议语义」，而领域层的 `Notification` 有自己更严格的不变量。

### 3.4 NotificationController：DTO ↔ 领域的翻译官

```java
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationCenter notificationCenter;

    public NotificationController(NotificationCenter notificationCenter) {
        this.notificationCenter = notificationCenter;
    }

    /** 发送通知：DTO -> 领域 -> 编排 -> View */
    @PostMapping
    public ApiResponse<NotificationView> send(@Valid @RequestBody SendNotificationRequest request) {
        Notification notification = (request.dedupeKey() != null && !request.dedupeKey().isBlank())
                ? Notification.withDedupe(request.tenantId(), NotifyChannel.WECHAT_WORK,
                        request.groupId(), request.title(), request.markdown(), request.dedupeKey())
                : Notification.wechatMarkdown(request.tenantId(), request.groupId(),
                     request.title(), request.markdown());

        Notification sent = notificationCenter.send(notification);
        return ApiResponse.ok(NotificationView.from(sent));
    }

    /** 查死信 */
    @GetMapping("/dead-letters/{tenantId}")
    public ApiResponse<List<NotificationView>> deadLetters(@PathVariable String tenantId) {
        List<NotificationView> views = notificationCenter.deadLetters(tenantId).stream()
                .map(NotificationView::from)
                .toList();
        return ApiResponse.ok(views);
    }

    /** 重投 */
    @PostMapping("/{id}/redeliver")
    public ApiResponse<NotificationView> redeliver(@PathVariable String id) {
        Notification n = notificationCenter.redeliver(id);
        return ApiResponse.ok(NotificationView.from(n));
    }

    /** 出站视图：只暴露调用方需要的字段 */
    public record NotificationView(String id, String channel, String target,
                                   String status, int attempts, String lastError) {
        static NotificationView from(Notification n) {
            return new NotificationView(n.id(), n.channel().name(), n.target(),
                    n.status().name(), n.attempts(), n.lastError());
        }
    }
}
```

要点：

- **`@Valid` 触发校验**：不合法直接被 GlobalExceptionHandler 接走，方法体内保证收到的是合法 DTO。
- **有/无 dedupeKey 两条构造分支**：调用不同静态工厂，把「是否幂等」的决策留在边界。
- **入站 DTO / 出站 View 双向隔离**：进来用 `SendNotificationRequest`，出去用 `NotificationView`，领域对象 `Notification` 从不越过 Controller 边界。

### 3.5 WebhookController：接住企业微信回调

```java
@RestController
@RequestMapping("/api/webhook")
public class WebhookController {

    private final WebhookInboundService inboundService;

    @Value("${notify.webhook.secret:demo-secret}")
    private String webhookSecret;

    public WebhookController(WebhookInboundService inboundService) {
        this.inboundService = inboundService;
    }

    @PostMapping("/wechat")
    public ApiResponse<String> receive(
            @RequestHeader("X-Event-Id") String eventId,
            @RequestHeader("X-Signature") String signature,
            @RequestBody String payload) {

        var result = inboundService.receive(eventId, payload, signature, webhookSecret);
        return switch (result.status()) {
            case REJECTED -> ApiResponse.fail(ApiResponse.CODE_INVALID_PARAM, result.message());
            case DUPLICATE, ACCEPTED -> ApiResponse.ok(result.status().name());
        };
    }
}
```

要点：

- **`@RequestHeader` 取签名与事件 ID**：Webhook 的验签信息在头里，业务体在 body。
- **密钥来自配置**：`@Value("${notify.webhook.secret:demo-secret}")`，**绝不硬编码**（避坑清单会重点讲）。
- **switch 表达式映射三态**：`REJECTED` 转失败，`DUPLICATE/ACCEPTED` 都算成功（重复投递对调用方而言就是「已收到」，返回成功让对方停止重试）。

### 3.6 AdminDashboardService + AdminController：死信运维闭环

```java
@Service
public class AdminDashboardService {

    private final NotificationCenter notificationCenter;

    public AdminDashboardService(NotificationCenter notificationCenter) {
        this.notificationCenter = notificationCenter;
    }

    public DeadLetterOverview deadLetterOverview(String tenantId) {
        List<DeadLetterItem> items = notificationCenter.deadLetters(tenantId).stream()
                .map(n -> new DeadLetterItem(n.id(), n.channel().name(),
                        n.target(), n.attempts(), n.lastError()))
                .toList();
        return new DeadLetterOverview(tenantId, items.size(), items);
    }

    public String redeliver(String id) {
        return notificationCenter.redeliver(id).status().name();
    }

    public record DeadLetterOverview(String tenantId, int count, List<DeadLetterItem> items) {}
    public record DeadLetterItem(String id, String channel, String target,
                                 int attempts, String lastError) {}
}
```

```java
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final AdminDashboardService dashboard;

    public AdminController(AdminDashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/dead-letters/{tenantId}")
    public ApiResponse<AdminDashboardService.DeadLetterOverview> deadLetters(@PathVariable String tenantId) {
        return ApiResponse.ok(dashboard.deadLetterOverview(tenantId));
    }

    @PostMapping("/dead-letters/{id}/redeliver")
    public ApiResponse<String> redeliver(@PathVariable String id) {
        return ApiResponse.ok(dashboard.redeliver(id));
    }
}
```

要点：

- **看板 = 数量 + 明细**：`DeadLetterOverview` 一次性给出「有多少条死信 + 每条为什么失败」，运维一眼定位。
- **复用 ApiResponse**：后台接口和对外接口共用同一响应壳，前端/工具解析逻辑统一。
- **`/admin` 路径独立**：网关可对 `/admin/**` 整段挂管理员鉴权，与对外 `/api/**` 隔离。

---

## 四、真实项目：一次完整的对外调用旅程

假设第十章的会议纪要 Workflow 跑完了，最后一步是「把纪要发给项目组」。看看从外部系统触发到落地的完整链路：

```
外部系统 / 前端 / 企微回调
        |
        | POST /api/notifications
        | { tenantId, groupId, title, markdown, dedupeKey }
        v
+-----------------------------+
| GlobalExceptionHandler 守门  |  <- @Valid 校验：任一字段非法 -> 400 统一响应
+-----------------------------+
        | (校验通过)
        v
+-----------------------------+
| NotificationController.send |  <- 翻译：DTO -> Notification 领域对象
+-----------------------------+
        | (dedupeKey 有值 -> withDedupe，走幂等分支)
        v
+-----------------------------+
| NotificationCenter.send     |  <- 第八章能力：选渠道 -> 幂等去重 -> 发送 -> 持久化状态
+-----------------------------+
        |
        +--> 成功: SENT   --> ApiResponse.ok(NotificationView{status:SENT})
        |
        +--> 3次失败: DEAD --> 进死信队列
                              |
                              v
                    +----------------------------+
                    | 运维在管理后台看到死信         |
                    | GET /admin/dead-letters/{t} |
                    +----------------------------+
                              | (排查后修复外部原因，人工兜底)
                              v
                    POST /admin/dead-letters/{id}/redeliver
                              |
                              v
                    NotificationCenter.redeliver -> 全新通知重新投递
```

**这条链路把前八章的能力真正「盘活」了**：

- 第八章的 `NotificationCenter`（幂等 / 重试 / 死信）本来只能在代码里调用，现在通过 `/api/notifications` 对外开放；
- 死信队列本来只是内存里的一个 List，现在通过 `/admin/dead-letters` 有了**可视化出口 + 人工兜底闭环**；
- 第十章终极场景的最后一步「发 Markdown 消息」，正是复用这里的发送接口。

**幂等的实战价值**：外部系统重试（网络抖动、超时重发）时带同一个 `dedupeKey`，`withDedupe` 分支保证「同一业务事件只真正发一次」——这是分布式系统「至少一次投递」下防重复的标准姿势。

---

## 五、避坑清单（12 条，血泪总结）

1. **Controller 里写业务逻辑，污染领域层。**
   Controller 只能做三件事：**校验、翻译、编排**。一旦在 Controller 里写 if-else 业务判断、拼装领域规则，业务逻辑就散落到了协议层，无法复用、无法单测。业务永远下沉到应用/领域层。

2. **不做参数校验，让脏数据直捣领域层。**
   没有 `@Valid` + Bean Validation，空 tenantId、超长 title 会一路冲进领域层甚至数据库。校验要在**边界**拦下，且用声明式注解而非手写 if。

3. **异常裸抛，把堆栈泄露给调用方。**
   直接 `throw` 未捕获异常，Spring 默认会把堆栈、类名、SQL 吐给调用方，这是**信息泄露安全漏洞**。必须有 `@RestControllerAdvice` 全局兜底，对外脱敏、对内记日志。

4. **业务码与 HTTP 状态码混为一谈。**
   把「业务拒绝」也返回 HTTP 500，会导致监控误报、重试逻辑错乱。规范：HTTP 码表达**传输层**结果，业务码（ApiResponse.code）表达**业务层**结果，二者分离。

5. **DTO 直接暴露领域对象。**
   用 `Notification` 领域对象直接接收请求 / 返回响应，会把领域内部结构、状态迁移方法暴露给外部，协议一变就得改领域。必须用 DTO（入站 Request、出站 View）做隔离带。

6. **`/admin` 后台接口不做鉴权。**
   死信重投、租户数据这类高危操作若不鉴权，任何人都能调 `/admin/**` 搞破坏。后台路径必须挂**管理员级鉴权**（生产接 Spring Security，按角色/权限控制）。

7. **没有限流，接口被打爆。**
   对外 `/api/**` 若无限流，恶意或异常调用方能瞬间打满线程池。生产要在网关层（或 Resilience4j / Sentinel）对租户维度限流。

8. **响应格式不统一，前端写一堆适配。**
   有的接口返回裸对象、有的返回 `{code,data}`、有的返回字符串，调用方要写 N 套解析。全站统一 `ApiResponse` 外壳，是团队协作的基本契约。

9. **多租户越权：不校验 tenantId 归属。**
   接口收了 tenantId 就直接查，A 租户能传 B 租户 ID 拿到别人数据。生产必须校验「当前登录身份是否有权访问该 tenantId」（本教学版聚焦架构，鉴权留作扩展）。

10. **Webhook 密钥硬编码在代码里。**
    `String secret = "abc123"` 写死在源码，一旦泄露（开源、反编译）就被伪造回调。必须走配置（`@Value` + 环境变量 / 配置中心），本章用 `${notify.webhook.secret:demo-secret}` 示范。

11. **Webhook 重复回调返回失败，导致对方无限重试。**
    企业微信等平台的 Webhook 是「至少一次」语义，同一事件会重发。若对重复事件（DUPLICATE）返回失败，对方会认为没送达而持续重试。正确做法：**重复也返回成功**（幂等已保证不重复处理）。

12. **死信队列只进不出，无运维出口。**
    通知失败三次进死信后，如果没有 `/admin/dead-letters` 看板和 `redeliver` 接口，这些消息就永远躺在那里「查无此人」。死信必须有**可视化 + 人工重投**的运维闭环，否则等于消息黑洞。

---

## 六、面试题精选

**Q1：六边形架构里的「入站适配器」和「出站适配器」分别是什么？Controller 属于哪一类？**

入站适配器（Driving / Primary Adapter）是**外部驱动应用**的入口——REST Controller、Webhook 接收器、消息队列消费者、定时任务等，它们把外部请求翻译成对应用层端口的调用。出站适配器（Driven / Secondary Adapter）是**应用驱动外部**的出口——数据库仓储实现、第三方 API 客户端、消息发送器等，它们实现应用层定义的出站端口。Controller 属于**入站适配器**，职责是校验、翻译（DTO↔领域）、编排，绝不含业务逻辑。这种分层让领域层完全不知道自己是被 HTTP 还是被 MQ 驱动，也不知道数据存哪里，达到「领域纯净、技术可替换」。

**Q2：为什么业务码要独立于 HTTP 状态码？举个混用会出问题的场景。**

因为二者表达的是不同层次的结果：HTTP 码是**传输/协议层**语义（请求有没有正确抵达、服务端有没有崩），业务码是**业务层**语义（业务规则是否通过）。混用会出问题：比如「用户余额不足」这种正常业务拒绝，如果返回 HTTP 500，会导致：① 监控系统把它统计成「服务端错误」，误报告警；② 网关/客户端的「5xx 自动重试」逻辑会对一个永远会失败的业务请求反复重试；③ 负载均衡可能据 5xx 把健康节点摘除。正确做法是 HTTP 200 + `code=业务错误码`，让传输成功与业务拒绝清晰分离。

**Q3：DTO 和领域模型为什么要分离？直接用领域对象当请求体有什么风险？**

分离是为了**隔离变化**并**保护领域不变量**。风险有三：① **协议耦合领域**——请求字段增减、校验规则调整属于协议层变化，若直接用领域对象接收，每次协议变动都要改领域模型；② **破坏封装与不变量**——领域对象通常「构造即合法」（私有构造 + 静态工厂 + 内部校验），而 JSON 反序列化会绕过构造器约束，可能造出非法领域对象；③ **暴露内部结构**——领域对象的内部方法（如状态迁移）、内部字段会被序列化暴露给外部。用 DTO（入站 Request + 出站 View）做隔离带，Controller 负责翻译，领域层保持纯净。

**Q4：Webhook 接口如何同时防伪造和防重复？返回码该怎么设计？**

**防伪造**：验签。发送方用共享密钥对 payload 做 HMAC-SHA256 生成签名放在请求头，接收方用同样密钥重算并**用常量时间比较**（防时序攻击）比对，不一致直接拒绝（REJECTED）。密钥必须走配置不能硬编码。**防重复**：幂等。Webhook 是「至少一次」语义会重发，接收方用事件唯一 ID（如 X-Event-Id）去重，已处理过的直接判为 DUPLICATE、不再执行业务。**返回码设计**：验签失败返回失败（让伪造方拿不到成功）；而 DUPLICATE 和 ACCEPTED **都要返回成功**——因为对发送方而言「重复事件」意味着「你之前其实已经收到了」，返回失败会让它误以为没送达而无限重试。

---

## 七、小结

| 构件 | 模块 | 职责 | 关键设计 |
| --- | --- | --- | --- |
| `ApiResponse<T>` | wechatapi/dto | 统一响应外壳 | 业务码独立 HTTP 码、泛型、静态工厂 |
| `GlobalExceptionHandler` | wechatapi/web | 全局异常兜底 | 三类分级、对外脱敏对内记日志 |
| `SendNotificationRequest` | wechatapi/dto | 入站 DTO | Bean Validation 声明式校验 |
| `NotificationController` | wechatapi/web | 通知入站适配器 | DTO↔领域翻译、出站 View 隔离 |
| `WebhookController` | wechatapi/web | Webhook 接入 | @RequestHeader 取签名、密钥走配置 |
| `AdminDashboardService` | wechatadmin/application | 后台运维逻辑 | 死信概览聚合、人工重投 |
| `AdminController` | wechatadmin/web | 后台入站适配器 | /admin 路径区隔、复用 ApiResponse |

本章补齐了六边形架构的**入站适配器**层，让前八章的能力从「代码内部可调用」升级为「外部可驱动、运维可掌控」的服务。核心心法一句话：**Controller 只翻译不干活，响应格式全站统一，异常绝不裸奔，能力对外要有门（鉴权/限流/校验），运维数据要有窗（死信看板）。**

---

## 八、扩展阅读

- **Spring MVC 官方文档** —— `@RestController` / `@RequestMapping` / `@RequestHeader` / `@RequestBody` 的完整用法。
- **Spring Framework：`@RestControllerAdvice` 与 `@ExceptionHandler`** —— 全局异常处理机制。
- **Bean Validation（Jakarta Validation 3.x）** —— `@NotBlank` / `@Size` / `@Valid` 及自定义校验注解。
- **Spring Security** —— 生产环境为 `/admin/**` 施加基于角色/权限的鉴权（本章留作扩展）。
- **Resilience4j / Sentinel** —— 接口限流、熔断、隔离。
- **OpenAPI / Swagger（springdoc-openapi）** —— 为 REST API 自动生成接口文档与在线调试页。
- **API 网关（Spring Cloud Gateway）** —— 统一鉴权、限流、路由、灰度，把横切关注点从业务代码中剥离。
- **RFC 9457（Problem Details for HTTP APIs）** —— 标准化的 HTTP 错误响应格式，可与 ApiResponse 思路对照。

---

## 九、下一章预告

第九章我们把平台的能力对外开放、把运维握在了手里——六边形架构的入站适配器闭环完成。至此，前九章的**所有积木**已经备齐：

> 消息模型 → 会话管理 → 消息路由 → 命令系统 → Memory/RAG → Workflow/Planning → 通知中心/Webhook → API 网关/管理后台。

**第十章《终极场景：会议纪要全链路》**，我们将把这些积木**串成一条完整的智能体流水线**，还原开篇那个场景：

> 用户在企业微信里发一句「帮我整理今天所有会议内容，并生成会议纪要，发送给项目组，同时把重要内容保存到知识库」。

Agent 将自动完成：识别用户身份 → 读取会话上下文 → Planning Agent 拆解任务 → Office Agent 生成纪要 → Browser Agent 上传归档 → 存入企业知识库 → Human Approval 人工确认 → 通过本章的通知接口发送 Markdown 消息 → 全程记录操作日志。

这将是一次「所有章节能力的总合奏」——理解了它，你就真正掌握了一个企业级 AI Agent 平台是如何从一条消息走到一次完整业务交付的。下一章见。

---