# ADR-003：独立 wechatagent 编排模块

- **状态**：Accepted
- **日期**：2026-08-04

## 背景

前九章分别构建了 core / session / memory / router / command / notify / workflow / api / admin 九个能力模块。
终极场景「会议纪要全链路」需要把这些能力**串成一条端到端流水线**。
问题是：这段「用例编排」代码该放在哪里？

- 放进 `wechatapi`？会让接口层承担流程编排职责，越界；
- 放进 `wechatworkflow`？会让通用工作流引擎耦合具体业务用例；
- 散落在各模块？没有单一入口，链路不清晰。

## 决策

新建**独立的 `wechatagent` 模块**，作为顶层用例编排层：

- `MeetingSummaryOrchestrator`：用例落点，编排会话 + 工作流；
- `AgentController`：入站适配器，暴露 instruct / approve / reject；
- **依赖面最小化**：只依赖 `SessionService` + `WorkflowEngine`，通知能力已被工作流内部编织。

`wechatagent` 位于**依赖树的根**——它依赖别人，别人不依赖它。

## 后果

**正面**：
- 用例编排有单一、清晰的归属；
- 编排层可独立演进、独立测试（两个可 Mock 依赖）；
- 各能力模块保持通用，不被具体业务用例污染；
- 通过 ArchUnit 守护「不被反向依赖」的方向铁律。

**负面**：
- 多了一个模块；
- 新增业务用例需在此模块加编排器，需注意避免膨胀成「大杂烩」。

## 备选方案

- **复用 wechatworkflow 承载用例**：会污染通用引擎，被否决。
- **在 api 层直接编排**：违反分层，接口层不应含流程编排，被否决。