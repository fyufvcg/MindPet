# MindPet — AI 陪伴 + 行动智能体

## 项目定位

MindPet 是一个**有情感的行动智能体**，兼具两个看似矛盾的能力：

- **情感侧**：像朋友一样聊天，有记忆、有情绪感知、有成长轨迹
- **行动侧**：帮用户干活——查天气、订车票、打车、搜信息、浏览器操控、本地文件读写

**目标不是做"能聊天的工具人"，而是做"能干活的真朋友"。**

---

## 技术栈

| 层级 | 选型 |
|------|------|
| 框架 | Spring Boot 3.3 + Spring AI 1.0.0 |
| LLM | 豆包 (OpenAI 兼容，ChatClient 统一调用) |
| 对话记忆 | Redis (短期对话历史) + pgvector (长期语义记忆) |
| 向量 | 豆包 Embedding API (1024维) |
| 工具系统 | Spring AI @Tool 注解 + 意图分类过滤 |
| 数据库 | PostgreSQL 14+ (用户画像 / 长期记忆 / pgvector) |
| 浏览器 | Playwright Java SDK (不经过 MCP) |
| 地图搜索 | 腾讯地图 WebService API |
| 打车 | 滴滴 MCP 云端服务 |

---

## 核心架构原则

### 1. 聊天和存记忆解耦

对话 LLM **不调 save 工具**。MemoryCurator 在对话后独立运行，批量提取值得长期记住的信息。

```
用户消息 → ChatClient(只带功能工具，不带 save) → 回复
对话后 → MemoryCurator(独立 LLM + 严格 prompt) → 只存真正重要的
```

### 2. 记忆分层

| 层级 | 强度 | 内容 | 注入方式 |
|------|------|------|----------|
| 工作记忆 | 覆盖式更新 | 当前状态摘要、待跟进话题 | **每次对话注入** |
| 档案卡 | 增量更新 | 身份/偏好/关系/情感模式 | **每次对话注入** |
| 长期记忆 | 按需检索 + 遗忘 | 具体事实/事件 | RAG 检索 Top-K 注入 |
| 常规对话 | 不存 | 闲聊、一次性信息 | 不存储 |

### 3. 情感分析

三层架构：危险关键词硬编码 → LLM 结构化输出 → 6 条语境翻转规则纠正 LLM 盲区。

情感结果注入 system prompt + 传递给 MemoryCurator（高情感强度 → 高重要性）。

### 4. 工具系统

```
用户消息 → preCall(轻量 LLM) → 返回: {groups: "出行|weather|...", emotion, intensity}
         → 并行: emotionService.analyze() — 完整情感分析
         → getToolNames(组名) → 传给 ChatClient.tools()
         → profile 组不进入主对话（由 MemoryCurator 独立使用）
```

**7 个工具组**: weather, voice, translate, calc, 出行, search, browser, 本地

---

## 演进路线

### Phase 1：记忆防乱存 ✅ 已完成
- [x] MemoryCuratorService — 独立 LLM + 严格提取 prompt
- [x] 从主对话移除 profile 工具组
- [x] **渐进式触发**：首次 20→30→40→50（最大），每次审查上次触发以来的所有新消息

### Phase 2：记忆分层 ✅ 已完成
- [x] PgVectorMemoryService — importance/layer/emotion/access_count/last_accessed 字段
- [x] MemoryLayer 枚举 (IMPORTANT S=5.0 / REGULAR S=1.0)
- [x] 遗忘曲线 `R = e^(-t/S)` + retention_rate()
- [x] 检索时过滤 retention_rate < 0.1 的记忆
- [x] 自动清理 > 500 条 + prune 低价值记忆
- [x] RRF 融合 + 关键词多路召回 + Reranking

### Phase 3：情感分析 ✅ 已完成
- [x] EmotionService — 三层架构（危险检测 + LLM + 语境翻转 6 条）
- [x] 情感历史追踪 (Redis, 最多 50 条)
- [x] 情感趋势对比（好转/恶化/稳定）
- [x] 情感结果注入 system prompt

### Phase 4：工作记忆 ✅ 已完成
- [x] MemoryCurator 生成 working_summary + open_topics + current_emotion
- [x] 工作记忆注入 system prompt（每次对话）

### Phase 5：浏览器 & 新工具 ✅ 已完成
- [x] Playwright Java SDK 直接操控浏览器（不经过 MCP）
- [x] 12 个浏览器工具（navigate/click/type/snapshot/screenshot/...）
- [x] 本地文件操作（file_read/file_write/file_list/file_delete + 白名单）
- [x] 滴滴打车（searchPlace→estimateRide→createOrder→query/cancel）
- [x] 工具调用结果整段发送，闲聊分段发送

---

## 关键文件

### 核心服务

| 文件 | 职责 |
|------|------|
| `AiService.java` | LLM 对话核心，意图分类，并行 preCall+emotion，ChatClient 调用 |
| `AiConfig.java` | ChatClient.Builder Bean，Advisor 配置(ToolCallLimit 5→3轮)，工具回调包装 |
| `ToolCallLimitAdvisor.java` | 限制工具调用轮数(3轮)，防止 LLM 陷入循环 |
| `ToolUserContext.java` | ThreadLocal — userId 注入 + 工具调用标记(控制分段发送) |

### 记忆系统

| 文件 | 职责 |
|------|------|
| `MemoryCuratorService.java` | 独立 LLM 提取记忆，渐进间隔(20→50)，增量审查 |
| `PgVectorMemoryService.java` | pgvector 长期记忆，混合检索(RRF+关键词+Reranking)，遗忘曲线 |
| `ConversationMemoryService.java` | Redis 短期对话历史(200条，7天TTL) |
| `MemoryLayer.java` | 记忆分层枚举(IMPORTANT/REGULAR)，retention_rate() |
| `EmbeddingService.java` | 豆包 Embedding API (1024维) |

### 情感系统

| 文件 | 职责 |
|------|------|
| `EmotionService.java` | 三层情感分析(危险词+LLM+上下文翻转)，情感历史追踪 |

### 用户画像

| 文件 | 职责 |
|------|------|
| `UserProfileService.java` | 用户画像 KV 存储 (identity/preference/experience/state) |
| `UserInsightService.java` | 相处经验 + 自我成长 RAG (pgvector) |

### 浏览器

| 文件 | 职责 |
|------|------|
| `PlaywrightBrowserService.java` | 浏览器生命周期管理，ARIA快照，CSS选择器+文本兜底 |
| `tool/impl/BrowserTools.java` | 12 个 @Tool (navigate/click/type/snapshot/screenshot/hover...) |

### 出行

| 文件 | 职责 |
|------|------|
| `TencentMapService.java` | 腾讯地图 suggestion API (地点搜索) |
| `DiDiMcpClient.java` | 滴滴 MCP 云端客户端 (标准 JSON-RPC，key 认证) |
| `tool/impl/DiDiRideTool.java` | 5 个 @Tool (searchPlace/estimateRide/createOrder/query/cancel) |
| `tool/impl/TicketQueryTool.java` | 12306 车票查询 + 手动 MCP JSON-RPC |

### 本地文件

| 文件 | 职责 |
|------|------|
| `tool/impl/LocalFileTool.java` | 4 个 @Tool (read/write/list/delete)，白名单+多根目录+越权拦截 |

---

## 不要做的事

1. **不要让对话 LLM 调 save 工具**
2. **不要用纯规则做情感分析**
3. **不要让主对话上下文过大** — 意图分类过滤工具组
4. **不要一条消息就存一次** — 批量提取 + 重要性过滤
5. **不要所有记忆平等对待** — 不分层的记忆 = 垃圾堆
6. **不要用 Spring AI MCP 集成** — stdio 每次新进程丢失状态，SSE 启动时序崩溃。自己写 HTTP JSON-RPC 客户端
