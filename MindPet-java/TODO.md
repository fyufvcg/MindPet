# MindPet 后端 → AgentPet 前端 对接待办

> 更新: 2026-08-03

---

## ✅ 已完成

- [x] 四表管理 CRUD + 分页 + 搜索
- [x] 去掉 user_id 过滤
- [x] LLM 调用切到后端 `callJavaBackend()`
- [x] LLM 配置同步（API Key/模型/BaseURL/SystemPrompt）
- [x] 删除后端微信 Bot（统一用前端 wechatBot.ts）
- [x] 删除 `agent-runtime/`（旧 AgentExecutor 死代码）
- [x] 删除 `api/memory.ts`（前端本地记忆，1040行）
- [x] 删除 `api/localEmbedding.ts`（远端 embedding）
- [x] System Prompt 编辑（Settings 页 + 同步后端）
- [x] System Prompt 自动注入当前日期时间（精确到秒+星期）
- [x] Token 用量估算 + 来源区分（桌面/微信）+ 仪表板两版切换
- [x] 路线规划修复（routePlanning 支持坐标参数）
- [x] 浏览器统一到后端 CDP（优先连用户浏览器，失败自启独立 Edge）

---

## 🔴 还需处理

### 1. Token 用量 — 改估算为真实值
- 当前：前端用字符数估算（~1 token/2.5字）
- 待做：后端 `AiService.chat()` 返回真实 usage → NDJSON `token_usage` 事件
- 前端已经能接收 `token_usage` 事件，只需后端发出

### 2. MCP 工具同步到后端
- 前端 MCP 配置页添加的服务器，后端不知道
- LLM 走后端 → 前端 MCP 工具不会被调用
- 方案：`api:sync-mcp-config` 增加同步到后端 → 后端动态注册

### 3. 前端浏览器工具清理
- 前端 `tools/builtin/web/` 仍有 `browser_*` 工具注册
- LLM 已走后端 → 这些工具注册了但调用不到
- 可删除或保留作备用

---

## 🟡 可选优化

### 4. 前端文件操作 vs 后端
- 前端有 read/write/edit/move/delete 工具
- 后端 `LocalFileTool.java` 有 `allowed-roots` 白名单
- Chat 中的文件操作可能需要统一入口

### 5. 前端专属（不需要改）
| 功能 | 说明 |
|------|------|
| RPA 工作流 | 录屏+流程图，纯前端 |
| 截图/OCR | PaddleOCR，纯前端 |
| SSH | 远程执行，纯前端 |
| Live2D | 桌面宠物，纯前端 |
| 技能包 | `.agentpet` 导入，纯前端 |
| 会议/ASR | FunASR，纯前端 |
