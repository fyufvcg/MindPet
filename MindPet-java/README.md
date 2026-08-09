# MindPet

MindPet 是一套由桌面端 AgentPet 和 Java 后端组成的智能助手系统：桌面端负责交互界面、Live2D 宠物、文件/系统操作和本地能力；后端负责大模型编排、工具调用、微信消息接入、会话持久化、用户记忆和知识图谱。

本仓库是后端工程，前端工程位于：`C:\Users\17547\Desktop\AgentPet-main`。

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
          ├─ PostgreSQL + pgvector：画像、长期记忆、向量检索、知识图谱
          ├─ Redis：短期会话记忆、缓存和跨窗口/跨端状态
          ├─ LLM / Embedding：豆包 Ark 或兼容 OpenAI 协议的服务
          └─ 外部能力：天气、腾讯地图、百度语音、12306/菜谱/外卖等 MCP
```

### 职责边界

| 部分 | 主要职责 |
| --- | --- |
| AgentPet 前端 | 桌面 UI、聊天展示、流式事件消费、会话管理界面、权限确认、文件选择与预览、RPA 编辑器 |
| Electron 主进程 | 调用后端、管理 IPC、执行本地文件/Office/终端/浏览器能力、保存桌面资源 |
| MindPet 后端 | 对话上下文、LLM 调用、意图识别、Function Calling、工具注册、记忆写入与检索、微信通道 |
| PostgreSQL | 结构化数据、用户画像、长期记忆向量和知识图谱 |
| Redis | 短期对话记忆、运行时缓存和会话同步 |

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
- Spring Data Redis：Redis 访问
- Spring JDBC + PostgreSQL JDBC `42.7.3`
- PostgreSQL `14+` + `pgvector`：向量记忆和知识图谱检索
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

sql/migration_v2.sql        数据库迁移脚本
start.bat                   开发启动（mvn exec:java）
start_bot.bat               完整启动（打包 + Redis/MCP + java -jar）
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

必须安装：

- JDK `21+`
- Maven `3.6+`
- Node.js `20+`（前端建议使用 LTS）和 npm
- PostgreSQL `14+`，并启用 `pgvector`
- Redis `7+`

可选依赖：

- Python `3.10+` 和 `mcp-server-12306[http]`：车票查询/订票/抢票
- Node.js / `npx`：菜谱 HowToCook MCP
- Ollama + `bge-m3`：本地 Embedding
- Playwright 浏览器：浏览器自动化功能首次使用时需要安装浏览器

## 5. 后端配置

复制模板后填写本机配置：

```powershell
Copy-Item src/main/resources/application-template.yml src/main/resources/application.yml
```

至少配置 PostgreSQL、Redis 和 LLM：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://127.0.0.1:5432/mindpet
    username: postgres
    password: <postgres-password>
  data:
    redis:
      host: 127.0.0.1
      port: 6379
  ai:
    openai:
      api-key: <ark-api-key>
      base-url: https://ark.cn-beijing.volces.com/api/v3
      chat:
        options:
          model: <model-endpoint-id>

llm:
  api:
    key: <ark-api-key>
    url: https://ark.cn-beijing.volces.com/api/v3/chat/completions
  model: <model-endpoint-id>
```

天气、腾讯地图、百度 AI/ASR/TTS、Embedding、邮件、MCP 等配置见 `application-template.yml`。密钥只放在本地配置或环境变量中，不要提交 `application.yml`、`config.properties`、`.env` 或任何真实凭证。

初始化数据库：

```sql
CREATE DATABASE mindpet;
\c mindpet
CREATE EXTENSION IF NOT EXISTS vector;
```

然后执行 `sql/migration_v2.sql`。如果使用的是已有数据库，先确认迁移脚本中的表和向量维度与当前 Embedding 模型一致。

## 6. 启动流程

### 6.1 只启动后端

```powershell
cd D:\youkeda\MindPet-java
mvn compile
mvn exec:java
```

或者使用项目脚本：

```powershell
.\start.bat
```

完整 Bot 启动：

```powershell
.\start_bot.bat
```

该脚本会尝试启动 Redis、12306 MCP（`8000`）和 HowToCook MCP（`3000`），然后运行微信机器人。后端默认端口为 `8080`，可通过 `GET http://127.0.0.1:8080/api/desktop/health` 检查。

### 6.2 启动前端

```powershell
cd C:\Users\17547\Desktop\AgentPet-main
npm install
npm run dev
```

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

## 7. 具体业务流程

### 7.1 桌面端聊天流程

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

### 7.2 快捷聊天流程

桌面悬浮宠物的快捷输入使用 `POST /api/desktop/chat`，请求字段与流式接口相近，后端返回完整 JSON；它不需要前端逐条消费 NDJSON。

### 7.3 会话与记忆流程

前端启动时读取 `/api/desktop/sessions` 和对应消息；新建、更新、删除会话时同步后端。聊天完成后，后端把消息写入 Redis 短期上下文，并按重要性生成用户画像、长期记忆和知识图谱数据，后续请求通过相似度检索重新注入上下文。

### 7.4 微信流程

1. `start_bot.bat` 编译并启动后端及可选 MCP 服务。
2. Java 微信 Bot 登录并接收文本、语音、图片和文件。
3. Bot 将消息交给 AI Service；AI Service 读取历史上下文和记忆，执行工具调用。
4. 文本回复直接发送；语音先经百度 ASR 转文字，语音回复经百度 TTS 合成后发送；文件先解析，修改/生成后保存并回传。

## 8. 桌面端 API 清单

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

## 9. 常见问题

- **前端提示后端连接失败**：确认后端已经启动，并访问 `http://127.0.0.1:8080/api/desktop/health`；确认端口没有被防火墙或其他进程占用。
- **聊天能打开但没有回复**：检查 `application.yml` 中 `spring.ai.openai`、`llm.api` 的 Key、Base URL 和模型 ID；再看 Java 控制台日志。
- **记忆/知识图谱不可用**：确认 PostgreSQL 可连接、已安装 `vector` 扩展并执行迁移脚本；确认 Embedding 服务可访问。
- **历史消息为空**：确认 Redis 已启动且地址为 `127.0.0.1:6379`，并检查前端请求使用的 `userId` 是否为 `desktop-user`。
- **车票功能不可用**：确认 12306 MCP 正在监听 `8000`，并按项目约定配置账号 Cookie；不使用车票功能时可以跳过。
- **菜谱/外卖工具不可用**：确认对应 MCP 服务已启动，并检查 `app.food.*` 配置。
- **前端依赖安装失败**：使用 Node.js LTS，删除前不要随意清理已有构建目录；优先执行 `npm install`，原生依赖安装完成后再运行 `npm run typecheck`。

## 10. 安全与提交检查

- 不提交 `src/main/resources/application.yml`、`config.properties`、前端 `.env`、API Key、数据库密码和微信/12306 凭证。
- 文件、Shell、SSH、浏览器和 RPA 工具具有本机或远程执行能力，生产使用前应启用最小权限和人工确认。
- 提交前执行：

```powershell
git status --short
git ls-files | Select-String 'application.yml|config.properties|\.env'
mvn compile
cd C:\Users\17547\Desktop\AgentPet-main
npm run typecheck
```
