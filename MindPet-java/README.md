# MindPet

MindPet 是一套由桌面端 AgentPet 和 Java 后端组成的智能助手系统：桌面端负责交互界面、Live2D 宠物、文件/系统操作和本地能力；后端负责大模型编排、工具调用、微信消息接入、会话持久化、用户记忆和知识图谱。

本仓库同时包含 Electron 桌面端和 Java 后端：前端位于 `MindPet/`，后端位于 `MindPet-java/`。

## 当前桌面默认存储

桌面版默认使用内嵌 SQLite：会话、短期上下文、情绪记录、缓存、长期记忆、用户画像、知识图谱与 RPA 记录都写入同一个 `mindpet.db`。向量由 Embedding 服务生成，优先交给随 JAR 打包的 `sqlite-vec` 计算余弦距离；扩展无法加载时自动使用 Java 精确余弦计算。

Electron 安装包同时携带后端 JAR、精简 JRE 和各平台 sqlite-vec 原生库。终端用户无需安装 Java、PostgreSQL、Redis 或 Docker。源码开发只需要 JDK 21、Maven 和 Node.js；数据库表会在首次启动时自动创建。

MindPet 目前面向用户的部署方式是桌面安装包；仓库不再维护 Docker Compose 或云服务器部署流程。`sql/` 中的旧数据库迁移脚本只供历史数据迁移参考，不参与桌面版运行。旧 PostgreSQL/Redis 数据尚未自动导入 SQLite。

## 1. 系统架构

```text
AgentPet Desktop (Electron + React)
  ├─ 渲染进程：聊天、Agent 控制台、设置、日志、RPA、记忆管理
  ├─ Preload：通过 contextBridge 暴露受控 IPC API
  └─ 主进程：文件/Office、浏览器、Shell/SSH、MCP、Live2D/TTS、桌面自动化
          │ HTTP JSON / NDJSON
          ▼
MindPet 后端 (Spring Boot，默认 127.0.0.1:8080)
  ├─ /api/desktop：桌面端聊天、配置、技能和健康检查
  ├─ /api/desktop/sessions：会话与消息
  ├─ /api/desktop/memory：用户画像、长期记忆、记忆管理
  ├─ /api/desktop/knowledge-graph：知识图谱查询与重建
  └─ 微信机器人：微信消息、语音、图片和文件入口
          │
          ├─ SQLite + sqlite-vec：画像、长期记忆、向量检索、知识图谱
          ├─ SQLite TTL 表：短期会话记忆、缓存和跨窗口状态
          ├─ LLM / Embedding：豆包 Ark 或兼容 OpenAI 协议的服务
          └─ 外部能力：天气、腾讯地图、百度语音、12306/菜谱/外卖等 MCP
```

### 职责边界

| 部分 | 主要职责 |
| --- | --- |
| AgentPet 前端 | 桌面 UI、聊天展示、流式事件消费、会话管理界面、权限确认、文件选择与预览、RPA 编辑器 |
| Electron 主进程 | 调用后端、管理 IPC、执行本地文件/Office/终端/浏览器能力、保存桌面资源 |
| MindPet 后端 | 对话上下文、LLM 调用、意图识别、Function Calling、工具注册、记忆写入与检索、微信通道 |
| SQLite + sqlite-vec | 会话、缓存、用户画像、长期记忆向量和知识图谱 |
| SQLite TTL 表 | 短期对话上下文和情绪历史 |

## 2. 技术栈

### 前端 AgentPet

- Electron `39.2.6`、Electron-Vite `5`、Electron Builder `26`
- React `19.2`、TypeScript `5.9`、Vite `7`
- Zustand：前端状态管理
- Pixi.js `6` + `pixi-live2d-display`：Live2D 渲染与交互
- `@modelcontextprotocol/sdk`：MCP 客户端
- Playwright Core：浏览器自动化
- `@nut-tree/nut-js`：桌面自动化
- SQLite：本地桌面数据/兼容缓存
- `exceljs`、`docx`、`mammoth`、`pdf-lib`、`pdfkit`、`pptxgenjs`、`xlsx`：Office/PDF 处理
- `node-edge-tts`：语音合成；`ssh2`：SSH 连接；`ws`：WebSocket 能力

### 后端 MindPet

- Java `21`
- Spring Boot `3.3.1`，内嵌 Tomcat，Spring Web
- Spring AI `1.0.0`：ChatClient、OpenAI-compatible LLM、Tool Calling
- Jackson：JSON 序列化
- Spring JDBC + Xerial SQLite JDBC：本地数据库访问
- sqlite-vec：向量距离计算；无法加载时回退 Java 精确计算
- 微信 iLink SDK `2.3.3`：微信机器人通道
- Apache POI `5.2.5`、PDFBox `2.0.31`：文件解析
- Playwright Java `1.48.0`：浏览器操作
- Angus Mail：邮件收发
- MCP 客户端：12306、菜谱、外卖、滴滴等扩展能力
- Maven：依赖管理、编译和打包

## 3. 目录说明

### 后端

```text
src/main/java/
├─ com/weather/wechatbot/  Spring Boot 启动类
├─ bot/                    微信登录、消息接收和回复
├─ controller/             桌面端 REST API
├─ config/                 LLM、天气、地图、语音、邮件等配置
├─ model/                  请求、响应和领域模型
├─ service/                LLM、记忆、文件、天气、语音、票务等业务服务
├─ tool/impl/              Agent 工具实现
└─ util/                   HTTP、JSON、微信、铁路等工具类

src/main/resources/
├─ application-template.yml 配置模板
├─ application.yml          本地实际配置（不要提交）
└─ log4j2.xml               日志配置

sql/                        历史数据库迁移脚本（不参与桌面版运行）
..\XiaoqingDesktop.vbs      唯一源码启动入口：后台构建后端并启动 Electron
..\scripts\check.bat       可选的 SQLite 存储检查
```

### 前端

```text
src/main/                  Electron 主进程、本地能力和后端适配器
src/preload/               contextBridge 与 IPC 类型/API
src/renderer/src/pages/    Agent、聊天、控制、日志、设置页面
src/renderer/src/components 聊天、文件、会议、Live2D 等组件
src/main/backend-api.ts    后端聊天流适配器
resources/live2d/          Live2D 模型与 Cubism Runtime
```

## 4. 环境准备

源码开发必须安装：

- JDK `21+`
- Maven `3.6+`
- Node.js `20+`（前端建议使用 LTS）和 npm

可选依赖：

- Python `3.10+` 和 `mcp-server-12306[http]`：车票查询/订票/抢票
- Node.js / `npx`：菜谱 HowToCook MCP
- Ollama + `bge-m3`：本地 Embedding
- Playwright 浏览器：浏览器自动化功能首次使用时需要安装浏览器

数据库无需安装或手工初始化。Electron 首次启动时自动创建数据库；默认开发数据路径为 `%APPDATA%/mindpet/backend/mindpet.db`，也可以通过 `USER_DATA_PATH` 指定用户数据目录。

## 5. 启动流程

### 5.1 一键启动 Electron 和 SQLite 后端

在仓库根目录双击 `XiaoqingDesktop.vbs`。脚本在后台构建 Java 后端并启动 Electron，不显示或保留命令行窗口；应用界面与本地后端都就绪后脚本正常结束。启动进度写入 `%LOCALAPPDATA%\MindPet\logs\dev-launcher.log`，失败时会弹出具体步骤和日志路径。Electron 启动时自动运行该后端并使用 SQLite，无需单独启动 Redis、Docker 或另开前端命令。源码调试需要 JDK 21、Maven、Node.js 20 和 npm；已打包的桌面应用会自带 Java 运行时，直接打开应用即可。

后端默认端口为 `8080`，可通过 `GET http://127.0.0.1:8080/api/desktop/health` 检查。可选的存储检查入口是 `scripts/check.bat`。

### 5.2 前端开发命令

通常使用 `XiaoqingDesktop.vbs` 同时启动前后端。需要单独调试前端时，在 `MindPet` 目录运行 `npm run dev`；该方式要求后端 JAR 已构建。

常用命令：

```powershell
npm run typecheck       # 前后端 TypeScript 类型检查
npm run lint            # ESLint
npm run build           # 类型检查 + Electron 构建
npm run build:win       # Windows 安装包/构建产物
npm run start           # 预览已构建产物
```

前端默认把后端地址设为 `http://127.0.0.1:8080`。如需修改聊天适配器地址，可设置环境变量：

```powershell
$env:MINDPET_API_URL = "http://127.0.0.1:8080"
npm run dev
```

注意：部分历史 IPC 处理器仍直接使用 `http://127.0.0.1:8080`，变更端口时需要同步检查 `src/main/index.ts` 中的会话、记忆、技能和配置请求。

## 6. 具体业务流程

### 6.1 桌面端聊天流程

1. 用户在 AgentPet React 页面输入文字、选择图片或附件。
2. 渲染进程通过 `window.api` 调用 Preload 暴露的 IPC 方法。
3. Electron 主进程将消息转换为后端格式，取最后一条消息作为 `message`，之前的消息放入 `history`；本地图片会转为 Base64。
4. 主进程向 `POST /api/desktop/chat/stream` 发送 JSON，请求默认超时 120 秒。
5. 后端加载会话上下文、用户画像、短期记忆、长期向量记忆和知识图谱上下文，构造系统提示词。
6. 后端根据意图选择工具，并通过 Spring AI 调用 LLM；需要真实数据时执行天气、搜索、地图、文件、票务或 MCP 工具。
7. 后端以 NDJSON 逐行返回 `text_delta`、`text`、`token_usage`、`error` 等事件。
8. Electron 解析每一行事件并通过 IPC 推送到渲染进程，页面实时显示回复、工具进度、Token 使用量和生成文件。
9. 后端保存会话消息，更新短期/长期记忆和知识图谱；生成文件统一写入 `~/.mindpet/generated-files`，前端负责展示和下载。

核心请求示例：

```http
POST http://127.0.0.1:8080/api/desktop/chat/stream
Content-Type: application/json
Accept: application/x-ndjson
```

```json
{
  "userId": "desktop-user",
  "message": "北京今天天气怎么样？",
  "sessionId": "session-id",
  "messageId": 1,
  "mode": "chat",
  "contextRounds": 6,
  "activeSkills": [],
  "history": []
}
```

流式响应示例：

```text
{"type":"text_delta","content":"北京今天"}
{"type":"text_delta","content":"天气晴朗"}
{"type":"token_usage","promptTokens":120,"completionTokens":30,"totalTokens":150}
{"type":"text","content":"北京今天天气晴朗。"}
```

### 6.2 快捷聊天流程

桌面悬浮宠物的快捷输入使用 `POST /api/desktop/chat`，请求字段与流式接口相近，后端返回完整 JSON；它不需要前端逐条消费 NDJSON。

### 6.3 会话与记忆流程

前端启动时读取 `/api/desktop/sessions` 和对应消息；新建、更新、删除会话时同步后端。聊天完成后，后端把消息写入 SQLite 短期上下文，并按重要性生成用户画像、长期记忆和知识图谱数据，后续请求通过相似度检索重新注入上下文。

### 6.4 微信流程

1. 启动桌面端和本地后端后，在应用设置中启用并配置微信通道。
2. Java 微信 Bot 登录并接收文本、语音、图片和文件。
3. Bot 将消息交给 AI Service；AI Service 读取历史上下文和记忆，执行工具调用。
4. 文本回复直接发送；语音先经百度 ASR 转文字，语音回复经百度 TTS 合成后发送；文件先解析，修改/生成后保存并回传。

## 7. 桌面端 API 清单

| 前缀 | 用途 |
| --- | --- |
| `/api/desktop/chat/stream`、`/api/desktop/chat` | 流式/非流式聊天 |
| `/api/desktop/health` | 后端健康检查 |
| `/api/desktop/llm-config` | 读取/保存运行时 LLM 配置 |
| `/api/desktop/mcp-config` | 保存 MCP 配置 |
| `/api/desktop/skills` | 读取和保存技能配置 |
| `/api/desktop/sessions` | 会话增删改查和消息保存、摘要 |
| `/api/desktop/memory/*` | 画像、记忆列表、统计、导入导出、清理 |
| `/api/desktop/knowledge-graph/*` | 图谱查询、证据、删除和重建 |

接口完整实现以 `src/main/java/controller/` 为准；前端调用封装和 IPC 映射以 `C:\Users\17547\Desktop\AgentPet-main\src\main\backend-api.ts`、`src\main\index.ts` 和 `src\preload\index.ts` 为准。

## 8. 常见问题

- **前端提示后端连接失败**：确认后端已经启动，并访问 `http://127.0.0.1:8080/api/desktop/health`；确认端口没有被防火墙或其他进程占用。
- **聊天能打开但没有回复**：检查 `application.yml` 中 `spring.ai.openai`、`llm.api` 的 Key、Base URL 和模型 ID；再看 Java 控制台日志。
- **记忆/知识图谱不可用**：运行 `scripts\check.bat` 检查 SQLite 存储接口，并确认 Embedding 服务可访问。
- **历史消息为空**：确认 `data/desktop/mindpet.db` 存在，并检查前端请求使用的 `userId` 是否为 `desktop-user`。
- **车票功能不可用**：确认 12306 MCP 正在监听 `8000`，并按项目约定配置账号 Cookie；不使用车票功能时可以跳过。
- **菜谱/外卖工具不可用**：确认对应 MCP 服务已启动，并检查 `app.food.*` 配置。
- **前端依赖安装失败**：使用 Node.js LTS，删除前不要随意清理已有构建目录；优先执行 `npm install`，原生依赖安装完成后再运行 `npm run typecheck`。

## 9. 安全与提交检查

- 不提交 `src/main/resources/application.yml`、`config.properties`、前端 `.env`、API Key、数据库密码和微信/12306 凭证。
- 文件、Shell、SSH、浏览器和 RPA 工具具有本机或远程执行能力，生产使用前应启用最小权限和人工确认。
- 提交前执行：

```powershell
git status --short
git ls-files | Select-String 'application.yml|config.properties|\.env'
mvn compile
cd ..\MindPet
npm run typecheck
```
