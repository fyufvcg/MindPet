# 本地 Agent 与长任务

用户请求仍从 `DesktopController` 进入 `AiService`。模型客户端、模型协议、流式响应、工具选择及调用循环均使用 Spring AI 1.1.8。

`AgentTaskService` 在每次操作请求上添加 Spring AI `ToolCallAdvisor`。其 `TaskAdvisor` 子类仅使用初始化和逐轮请求钩子更新工具集合、检查预算、整理上下文；同步与流式的循环、工具消息配对由 Spring AI 实现。`ToolCallingManager` 的装饰器负责在调用前保存执行意图、在返回后保存结果并加入图片观察，执行仍委托给 Spring AI 的默认 manager。

## 执行与工具

- 原有意图路由只提供初始工具集合，不再强制调用命中的工具。
- `agent_discover_tools` 查询工具组或按关键词加载新工具。工具集合在框架的下一轮请求前刷新；浏览器控制继续遵守明确请求的限制。
- `agent_update_plan` 保存多步计划与进展，事件通过现有工具步骤展示。
- `agent_report_completion` 保存实际验证证据或缺少的输入。有计划但没有完成报告的任务记为 `needs_input`，而不是自动标记完成。
- `agent_read_output` 使用 `call_id`、`offset`、`length` 分页读取本任务的归档输出。模型可见的单次工具输出过长时会携带归档引用。
- 连续两次失败的相同工具和参数受到阻止；不同参数、不同文件的正常操作不会因工具名称相同而受限。连续四次完全相同的成功操作受到停滞保护。
- 模型或工具失败不会重发整轮任务，避免重复副作用。

默认任务预算为 80 轮模型请求、200 次工具调用、30 分钟。可通过 Spring 配置覆盖：

```yaml
agent:
  max-model-turns: 80
  max-tool-calls: 200
  timeout-minutes: 30
  context-max-chars: 160000
```

`context-max-chars` 是文本字符预算，不等于模型 token 上限。压缩保留系统指令、原始目标、计划、工具输出索引与近期完整调用周期；大结果保存在 SQLite。历史图片只保留最近两次观察。超长系统提示或用户目标本身仍可能超过模型上下文限制。

## 本地工具桥接

Electron 为当前请求登记窗口与工作目录。Java 通过 MCP `params._meta["mindpet/context"]` 传递任务、会话、消息、期限和调用 ID，工具业务参数无需包含这些字段。运行目录从当前请求登记信息取得，未指定时使用会话目录。

返回同时包含标准 MCP `content`、`isError` 与结构化结果。截图、图片读取和 Office 预览的图片通过 MCP image content 回传，Java 将其转换成 Spring AI `Media`，供下一轮模型观察。图片采用大小和数量限制，未作为 base64 写入检查点。

用户点击停止时，Electron 立即取消本地执行并向 Java 发送取消请求；Java 取消模型流或等待任务，MCP 连接断开也会取消本地工具。已完成的文件修改不会回滚。权限确认等待支持取消，执行结束清理本轮授权。仍需各具体工具正确处理 `AbortSignal`，无法强制撤销已经提交的外部动作。

## 检查点与恢复

新增 SQLite 表 `agent_task` 与 `agent_tool_output`，与长期记忆分开。保存计划、完整工具输出、消息配对和执行状态，敏感字段在持久化前脱敏。服务启动将遗留 `running` 标记为 `interrupted`。

每批工具执行前先保存包含调用意图的检查点；每个工具返回后保存结果。恢复时已知结果继续使用；没有结果的调用补充 `INTERRUPTED_UNKNOWN_OUTCOME`，提示先检查环境。不会自动重新执行这些写操作。图片与脱敏凭据需要重新获取。

在原会话发送“继续任务”“恢复上次任务”或“继续”，会选取该会话最近一次可恢复任务。也可以使用 API 精确恢复：

| API | 用途 |
| --- | --- |
| `GET /api/desktop/agent/tasks?userId=desktop-user&sessionId=...` | 列出会话任务及状态 |
| `GET /api/desktop/agent/tasks/{taskId}?userId=desktop-user` | 查看检查点 |
| `POST /api/desktop/agent/tasks/cancel` | 按 `taskId` 或 `sessionId` / `messageId` 取消 |
| `POST /api/desktop/agent/tasks/{taskId}/resume` | 显式恢复，返回与聊天相同的 NDJSON 流 |

恢复 API 的 body 可包含 `userId`、`activeSkills`、`systemPrompt` 等本次请求配置；前端聊天接口也接受 `resumeTaskId`。恢复会使用当前模型、政策与当前启用的技能指令，不恢复旧系统指令或旧授权。

这些能力提供活动会话内的长任务及中断后的显式恢复。应用退出后不会自动继续操作电脑；也没有保证任意外部副作用的 exactly-once 执行。

## 联网原则

路由、主系统提示与联网工具说明统一要求优先依据已有知识。只有最新、易变信息或用户明确要求联网核实、查找来源时才搜索。常识、概念、推理和一般建议无需搜索。空结果属于正常结果，与调用错误区分；失败后应说明实际限制，在标明不确定性的前提下继续尽力回答。

## 验证

`AgentTaskServiceTest` 使用真实 `ChatClient`、`ToolCallAdvisor`、默认 `ToolCallingManager` 和临时 SQLite，模型响应由脚本提供；验证连续多次调用、动态发现、恢复、取消、图片、预算、上下文压缩和输出归档。`AgentMcpBridgeTest` 使用本机 HTTP MCP 服务检查元数据与错误语义。测试不连接真实模型，也不操作用户文件或桌面。
