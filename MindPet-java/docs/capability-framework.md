# 能力加载与任务框架

## 能力边界

MindPet 使用单个主 Agent 理解请求、选择能力并持续执行。Java 继续使用 Spring AI `ChatClient`、`ToolCallAdvisor` 和默认 `ToolCallingManager`，应用层负责能力目录、任务状态和执行适配。不再发送独立的前置工具路由模型请求。情感分析使用独立后台队列；本轮危险信号规则立即执行，不等待情感模型。

| 类型 | 来源 | 默认行为 |
| --- | --- | --- |
| Java 基础工具 | 明确注册的 Spring AI 工具 Bean | 名称、说明和参数常驻 |
| Desktop Tool | Electron 内部 MCP 桥 | 名称、说明和参数常驻，无需发现或 `/desktop` |
| Skill | 后端内置资源、用户安装的技能包 | 简介进入目录，正文和资源按需加载，无启用开关 |
| 用户 MCP | 用户配置的外部服务 | 通过聊天 `/` 选择进入当前任务 |

内部 Desktop 使用 MCP 协议是传输细节。Electron 将它登记为 `DESKTOP_BUILTIN`，Java 根据固定内部桥身份和端点确认来源；其他连接归为 `USER_MCP`。模型工具集合通过连接元信息区分来源，不依赖工具名称猜测。

## 常驻与渐进式披露

每轮模型请求包含 Agent 基础规则、相关记忆、基础工具定义和 Skill 简介目录。简介目录使用约 5,000 字符预算，剩余 Skill 可通过搜索或分页查找。没有将全部 Skill 正文拼接进 system prompt。

1. `agent_discover_skills(query, offset, limit)` 返回内置和已安装 Skill 的稳定 ID、简介、来源与版本。发现使用本地关键词、中文双字片段匹配；它不是语义搜索，也不额外调用模型。
2. `agent_load_skill(id)` 记录任务已加载的 Skill。下一轮 Spring AI 请求实际加入其正文；重复加载复用同一 ID。
3. `agent_read_skill_resource(id, path, offset, length)` 分页读取已加载 Skill 的正文或参考资料、脚本。`SKILL.md` 表示正文。脚本读取不等于执行，执行使用已有终端工具。

已加载正文按 Token 预算分配：单 Skill 最多约 2,400 Token，合计使用输入预算的五分之一，最近加载者优先；被截断内容标明分页入口。阶段结束可用 agent_release_capabilities 回收正文与展开的外部 MCP 定义，之后仍可重新加载。见 [上下文管理](context-management.md)。导入时收集文本参考资源，每个资源最多 256 KB、每个 Skill 合计最多 2 MB，扫描深度最多 6 层、最多 500 个文件。其他资源仍保留在原目录，可使用返回的 `rootPath` 和文件工具查看。超过限制的参考文件不会进入后端文本快照。

Skill 正文是操作参考，不能覆盖用户请求和系统规则，不会自动获得其他 MCP 服务或额外操作权限。

## Skill 存储和前端导入

内置 Skill 位于 `src/main/resources/skills/`，第一批提供：

- `builtin/local-files`：本地资料查找、读取、分类与整理。
- `builtin/office`：办公入口，按格式加载既有 Office 操作能力。
- `builtin/web-research`：遵循知识优先原则的联网资料整理。

用户可导入 ZIP 技能包，也可在技能存放目录放置包含 `SKILL.md` 的目录。一个包中的多个入口分别登记，使用 `user/包名/相对目录` 稳定 ID，与内置 ID 隔离。用户 Skill 不覆盖内置 Skill。

前端读取现有元信息中的 description；缺失时从正文或标题提取简短介绍，不需要单独的模型请求。管理页展示介绍，用户可编辑，失焦后保存到技能目录 `.mindpet-skill.json` 并同步。内置 Skill 只读。

前端在扫描、导入、删除、编辑，以及普通前台模型请求前同步完整的已安装目录。空快照也同步，用于清除已卸载的用户 Skill。同步传输不等于模型上下文加载。旧 `getActiveSkillsPrompt` IPC 仅保留兼容同步行为，返回空字符串，不再注入正文。

后端保留兼容的 `POST /api/desktop/skills`，接受旧字符串和新结构：

```json
{
  "user/example": {
    "description": "简短介绍和适用场景",
    "content": "SKILL.md 正文",
    "rootPath": "本地技能目录",
    "resources": { "references/example.md": "参考内容" }
  }
}
```

`GET /api/desktop/skills/catalog` 返回不含正文的完整元信息目录。后端持久化用户快照到兼容的 `skills-config.json`，启动时合并内置 Skill。

## 外部 MCP 的 `/` 选择

聊天输入框输入 `/` 显示用户配置的外部服务。支持搜索、上下方向键、Enter 或 Tab 选择、Escape 关闭，也可点击选择。所选服务显示为可移除标签。输入 `/服务名 请求内容` 或 `/服务ID 请求内容` 也支持解析；重名服务请通过选择器明确选择。

请求通过 `selectedMcpServerIds` 传递真实服务 ID。前端不会只把一段命令文本交给模型猜测。Desktop 不出现在这个列表中。

用户发送后，选择作用于本次任务。执行中追加指引继承该任务的服务范围；新独立任务默认不继承。恢复任务会从后端检查点重新取得此前明确选择的服务。配置页保留地址、认证、测试连接与删除操作，取消全局启用开关；旧 `enabled` 字段仅用于存储兼容。

每个已选服务工具数不超过 20 时直接提供完整工具定义；超过 20 时先提供服务摘要，使用 `agent_discover_tools` 按关键词或服务 ID 加载。未选择服务的回调不会进入当前任务的工具目录。连接配置仍可在配置同步或连接检测期间初始化，`/` 控制的是任务工具范围。

## 检查点与恢复

任务快照除已有计划、工具结果、进度、产物和输出索引外，还保存：

- `selectedMcpServerIds`：本次任务已选外部服务。
- `loadedSkills`：已加载 Skill ID 与版本。
- `pinnedSkills`：当前阶段持续依赖的 Skill 引用；正文仍受整体预算约束。
- `enabledTools`：已加载工具名称。

恢复时重新构建当前工具范围，从当前安装目录重建 Skill 内容；已卸载 Skill 和已移除工具不会重新加载。Skill 内容版本使用 SHA-256 摘要，恢复使用当前版本。不会恢复旧系统提示或旧操作授权。

Skill 动态系统正文不重复写入消息检查点，能力引用单独保存。工具输出仍使用已有 SQLite 归档和分页读取机制。

## 代码位置

- `config/SkillStore.java`：合并目录、元信息、版本和简介预算。
- `service/agent/AgentTaskService.java`：按需加载、逐轮注入、工具发现和检查点。
- `service/AiService.java`：常驻工具集合、已选 MCP 范围与系统上下文。
- `service/McpManager.java`：连接来源、工具归属和任务范围过滤。
- `MindPet/src/main/skills/catalog.ts`：导入解析和文本资源包装。
- `MindPet/src/renderer/src/hooks/useTaskMcp.ts`：任务内 MCP 选择与追加指引继承。
- `MindPet/src/renderer/src/pages/ChatPage.tsx`：`/` 选择器及 Skill 简介。
- `MindPet/src/renderer/src/pages/AgentPage.tsx`：Skill 简介编辑与外部服务管理。

阶段切换可在 `agent_update_plan` 中声明 `active_skill_ids` 与 `active_tool_names`，更新计划时同步回收已结束阶段的正文与外部工具定义；不影响常驻 Desktop 和 Java 工具。重要用户要求通过 `agent_update_contract` 保存原文和来源，持续显示于任务上下文，不依赖反复摘要，也不写入长期记忆。
