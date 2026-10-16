# 本地 Agent 与长任务

用户请求仍从 `DesktopController` 进入 `AiService`。模型客户端、模型协议、流式响应、工具选择及调用循环均使用 Spring AI 1.1.8。

`AgentTaskService` 在每次操作请求上添加 Spring AI `ToolCallAdvisor`。其 `TaskAdvisor` 子类仅使用初始化和逐轮请求钩子更新工具集合、检查预算、整理上下文；同步与流式的循环、工具消息配对由 Spring AI 实现。`ToolCallingManager` 的装饰器负责在调用前保存执行意图、在返回后保存结果并加入图片观察，执行仍委托给 Spring AI 的默认 manager。

## 执行与工具

- 取消前置工具路由模型调用。Desktop 和 Java 基础工具常驻提供，主 Agent 直接判断任务与操作。
- 用户配置的外部 MCP 通过聊天 `/` 选择加入当前任务；大型服务用 `agent_discover_tools` 按服务 ID 或关键词加载更多工具。未选服务不进入当前工具目录。
- Skill 简介合并进入常驻目录，使用 `agent_discover_skills`、`agent_load_skill`、`agent_read_skill_resource` 按需发现、加载正文及分页读取资源；不再使用 Skill 开关或前端全文注入。具体机制见 [能力框架](capability-framework.md)。
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
  context:
    window-tokens: 65536
    max-input-tokens: 32000
    output-reserve-tokens: 8192
    safety-tokens: 2048
    task-soft-tokens: 120000
    task-max-tokens: 400000
```

上下文由独立的 Token 预算模块组装，包含工具定义和图片预留；固定轮数和字符阈值已退出桌面端运行策略。历史原文、任务检查点和能力正文分别管理，见 [会话上下文管理](context-management.md)。

## 本地工具桥接

外部 MCP 协议统一由 Electron 的官方 `@modelcontextprotocol/sdk` 处理，支持 Streamable HTTP 与 SSE；Java 只保留到 Electron 的本机桥接和 Spring AI ToolCallback。外部服务使用包含服务 ID 的稳定工具标识，同名工具不会串到另一个服务；工具错误、非文本内容与结果未知状态跨桥接保留。

外部服务凭据保存在 Electron 凭据库中；同步给 Java 的只有服务身份、目录版本和本机桥接信息。桥接使用每次启动生成的鉴权令牌，仅在进程内保留。配置变化会失效旧连接，目录刷新读取实时分页目录。

Electron 为当前请求登记窗口与工作目录。Java 通过 MCP `params._meta["mindpet/context"]` 传递任务、会话、消息、期限和调用 ID，工具业务参数无需包含这些字段。运行目录优先使用当前请求登记信息，后台计划使用执行元数据中的工作目录，未指定时使用会话目录。

返回同时包含标准 MCP `content`、`isError` 与结构化结果。截图、图片读取和 Office 预览的图片通过 MCP image content 回传，Java 将其转换成 Spring AI `Media`，供下一轮模型观察。图片采用大小和数量限制，未作为 base64 写入检查点。

用户点击停止时，Electron 立即取消本地执行并向 Java 发送取消请求；Java 取消模型流或等待任务，MCP 连接断开也会取消本地工具。已完成的文件修改不会回滚。权限确认等待支持取消，执行结束清理本轮授权。仍需各具体工具正确处理 `AbortSignal`，无法强制撤销已经提交的外部动作。

## 检查点与恢复

新增 SQLite 表 `agent_task` 与 `agent_tool_output`，与长期记忆分开。保存计划、完整工具输出、消息配对和执行状态，敏感字段在持久化前脱敏。服务启动将遗留 `running` 标记为 `interrupted`。

每批工具执行前先保存包含调用意图的检查点；每个工具返回后保存结果。恢复时已知结果继续使用；没有结果的调用补充 `INTERRUPTED_UNKNOWN_OUTCOME`，提示先检查环境。结果未知的调用按规范化参数记录，改换 JSON 字段顺序也不能直接重放。`agent_confirm_outcome` 必须引用之后成功执行的实际工具检查，旧观察或内部规划调用不能作为核验。不会自动重新执行这些写操作。图片与脱敏凭据需要重新获取。

在原会话发送“继续任务”“恢复上次任务”或“继续”，会选取该会话最近一次可恢复任务。也可以使用 API 精确恢复：

| API | 用途 |
| --- | --- |
| `GET /api/desktop/agent/tasks?userId=desktop-user&sessionId=...` | 列出会话任务及状态 |
| `GET /api/desktop/agent/tasks/{taskId}?userId=desktop-user` | 查看检查点 |
| `POST /api/desktop/agent/tasks/cancel` | 按 `taskId` 或 `sessionId` / `messageId` 取消 |
| `POST /api/desktop/agent/tasks/{taskId}/resume` | 显式恢复，返回与聊天相同的 NDJSON 流 |

恢复 API 的 body 可包含 `message`（补充要求）、`userId`、`selectedMcpServerIds`、`systemPrompt` 等本次请求配置；前端聊天接口也接受 `resumeTaskId`。检查点保存已选外部服务和已加载 Skill 引用，恢复时从当前目录重建；按本次选择重新确定外部 MCP 范围，不恢复已撤销的服务、旧系统指令或旧授权。任务记录页提供上述入口，不需要手工调用 API。

这些能力提供活动会话内的长任务及中断后的显式恢复。应用退出后不会自动继续操作电脑；也没有保证任意外部副作用的 exactly-once 执行。

## 后台定时任务

SQLite `agent_schedule` 保存计划、下次触发时间和最近 100 次执行日志。`AgentScheduleStore` 管理配置、事务领取与完成状态；`AgentScheduleService` 负责有界调度；`AgentScheduledTaskRunner` 复用 `AiService` 与 Spring AI。设置页、聊天工具和旧数据迁移共用同一 API，不再由渲染窗口倒计时执行，也不继续向旧 JSON 文件写入计划。

同一计划不重叠执行，不补跑退出期间的积压；每次结束后按间隔安排下一次。失败、等待输入或进程退出导致结果未确认时暂停计划，先在任务记录中核对。运行中的计划须先停止实际任务，再删除计划。退出应用后无法继续操作电脑。

编辑器只提交配置，后台日志和运行状态不被旧界面副本覆盖。任务级工作目录与 MCP 范围持久化；Desktop 基础工具始终可用。旧 JSON 与浏览器计划合并后一次迁移，源文件保留为迁移备份，删除的计划不会被再次导入。

已完成任务及工具输出默认保留 90 天（`agent.completed-retention-days`，小于等于 0 时禁用）。可恢复任务、会话历史和长期记忆不在此清理范围内。

## 联网原则

主系统提示、内置联网 Skill 与联网工具说明统一要求优先依据已有知识。只有最新、易变信息或用户明确要求联网核实、查找来源时才搜索。常识、概念、推理和一般建议无需搜索。空结果属于正常结果，与调用错误区分；失败后应说明实际限制，在标明不确定性的前提下继续尽力回答。

结构化搜索在 Electron 主进程读取环境变量：

| 变量 | 说明 |
| --- | --- |
| `MINDPET_SEARCH_PROVIDER` | `auto`（默认）、`tavily`、`brave` 或 `browser` |
| `TAVILY_API_KEY` | Tavily 搜索凭据 |
| `BRAVE_SEARCH_API_KEY` | Brave 搜索凭据 |

`auto` 优先选择已配置的 Tavily，其次 Brave，否则浏览器搜索。结构化接口返回标题、URL 与摘要，不自动抓取结果页面；成功结果缓存 60 秒。接口失败后，在剩余超时预算内最多尝试一次浏览器搜索，向模型返回具体限制。配置环境变量后需要重新启动 Electron；当前尚无搜索服务设置界面。

## 验证

`AgentTaskServiceTest` 使用真实 `ChatClient`、`ToolCallAdvisor`、默认 `ToolCallingManager` 和临时 SQLite，模型响应由脚本提供；验证连续多次调用、动态发现、恢复、取消、图片、预算、上下文压缩和输出归档。`AgentMcpBridgeTest` 使用本机 HTTP MCP 服务检查元数据与错误语义。测试不连接真实模型，也不操作用户文件或桌面。


## 搜索效率与用量

搜索摘要已经覆盖问题所需事实、目标日期和来源时直接回答。仅在关键字段缺失、来源冲突、需要核对原文或用户要求深入研究时使用 `web_fetch`。网页抓取返回最多 4000 字符的正文片段，可通过 `query` 提供关键词并返回带行号的相关片段；完整正文缓存保留，但不要求全文读取。

桌面历史由后端从独立归档读取，按模型容量和任务预算保留检查点及近期完整交互。大工具结果外置并提供实际分页位置；不会按工具消息数量丢弃仍需使用的结果。旧界面截图由后续观察替代，用户图片可显式回读。

Spring AI 的逐轮回调负责累计用量；已累计的响应标记为 `mindpet.usageAggregated`，最外层流不会再次累加。检查点保存 `usageRounds`、`currentUsage` 和累计 prompt/completion token；每轮记录文本上下文字符数，用于定位开销。缺失模型用量使用运行输入估算并显式标记 estimated；运行期摘要成本也计入任务。旧日志不自动改写。
