<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://readme-typing-svg.demolab.com?font=Noto+Sans+SC&weight=600&size=32&duration=3000&pause=1000&color=58A6FF&center=true&vCenter=true&width=600&lines=MindPet%EF%BC%8C%E8%AE%B0%E4%BD%8F%E4%BD%A0%E3%80%82" />
  <img src="https://readme-typing-svg.demolab.com?font=Noto+Sans+SC&weight=600&size=32&duration=3000&pause=1000&color=0969DA&center=true&vCenter=true&width=600&lines=MindPet%EF%BC%8C%E8%AE%B0%E4%BD%8F%E4%BD%A0%E3%80%82" alt="MindPet，记住你。" />
</picture>

<p align="center">
  <em>A companion that remembers — 跨平台 · 跨会话 · 越聊越懂你</em>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License" />
  <img src="https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-brightgreen.svg" alt="Platform" />
  <img src="https://img.shields.io/badge/java-21-orange.svg" alt="Java 21" />
  <img src="https://img.shields.io/badge/electron-39-9cf.svg" alt="Electron 39" />
</p>

---

## ✨ 什么是 MindPet

MindPet 是一个**桌面 AI 智能伴侣**——不是存日志的聊天机器人，而是会理解什么重要、选择什么留下的长期伙伴。它拥有长期记忆、情感感知、知识图谱和自主工具调用能力，跨会话、跨入口，每次打开都是接着聊。

> **不是存日志，是理解什么重要，选择什么留下。**

---

## 🧠 核心能力

### 1. 长期记忆 —— 选择性记住该记住的

MindPet **不会把每条消息都塞进数据库**。消息先进入短期上下文窗口，由 LLM 结合知识图谱评估重要性：有价值的事实写入 PostgreSQL 长期存储，闲聊只在短期窗口里保留，过期自然消失。

| 机制 | 说明 |
|------|------|
| **重要性评估** | LLM 判断每条内容的长期价值，只有达到阈值的才存入长期记忆 |
| **混合检索** | 语义向量 + 关键词 + RRF 融合 + Reranking，TopK 精准召回 |
| **三层画像** | 事实记忆 → 用户画像 → 相处经验，逐层沉淀 |
| **自动遗忘** | `importance × e^(-age/decay)`，琐事快忘、重要的事慢忘 |

```
用户消息 → 短期上下文 (Redis) → LLM 评估重要性
                                      │
                         ┌────────────┼────────────┐
                         ▼            ▼            ▼
                    重要事实      普通对话      闲聊/噪音
                  写入 PG         短期保留      直接丢弃
                  向量索引       过期消失
```

### 2. Memory Curator —— 记忆馆长

每 **15 轮对话**自动唤醒，跨会话审查最近 20 轮对话：

- **去重合并**：同一件事说了多次？合并为一条更完整的记忆
- **反思提炼**：从碎片化事实中总结用户偏好和行为模式
- **画像更新**：维护用户画像、相处经验、LLM 自我成长三个维度
- **不膨胀**：确保记忆库精简、不矛盾

### 3. 情感感知 —— 读懂你的弦外之音

真正的陪伴不是看见「开心」就庆祝，而是知道你在**苦笑、强撑，还是终于松了一口气**。

MindPet 采用**三层情感分析架构**：

| 层级 | 职责 | 示例 |
|------|------|------|
| **① 安全筛查** | 硬编码危险信号关键词，不可遗漏 | 自杀、自残等危机信号 |
| **② LLM 判断** | 结构化输出 `emotion` + `intensity` + `triggers` | 11 种情绪分类 |
| **③ 语境翻转** | 6 条规则纠正 LLM 盲区 | 见下表 |

| 用户说 | 表面情绪 | MindPet 真正理解 |
|--------|----------|-----------------|
| 「哈哈，老板周末凌晨三点发消息」 | 开心 | 😮‍💨 **苦笑 / 自嘲** |
| 「算了，反正没人在乎」 | 无所谓 | 💔 **强装镇定** |
| 「终于考完了」 | 焦虑 | 😌 **如释重负** |

> 支持 **11 种情绪**：开心 / 难过 / 焦虑 / 生气 / 平静 / 兴奋 / 压力 / 释然 / 感恩 / 孤独 / 疲惫

### 4. 知识图谱 —— 记忆可视化

记忆不是黑盒。知识图谱自动将人物、事件、偏好整理成可探索的**关系网络**。

<p align="center">
  <b>实体 → 关系 → 证据链</b><br/>
  每条关系都能追溯到原始对话，也可以手动编辑或删除
</p>

| 能力 | 说明 |
|------|------|
| **自动构建** | 从对话中抽取实体（人/事/物）和关系，实时更新图谱 |
| **可视化浏览** | 记忆星图交互探索，按类型、时间、关联度筛选 |
| **可溯源** | 每条边都有证据链，点击回到原始对话 |
| **可编辑** | 用户可以直接修改关系、删除错误记忆 |

---

## 🔌 能力扩展：MCP + Skill

MindPet 不是封闭系统。通过 MCP 协议和 Skill 规约，**任何人都可以给它添加新能力**。

### MCP 自主接入

**填一个 URL，自动发现全部工具：**

```
用户界面填入 MCP Server URL
        │
        ▼
  McpManager 自动连接 ──→ 发现 tools[] 列表
        │
        ▼
  注册为 LLM 可调用工具 ──→ 对话中按需触发
```

- 支持 **SSE / Streamable HTTP / Auto** 三种传输协议
- 工具发现**自动缓存**，断线自动重连
- 前后端双 MCP 管理层：Electron 端 + Java 端均可接入

### 内置 MCP

项目预置了常用 MCP 服务，开箱即用：

| 服务 | 能力 |
|------|------|
| 🍳 **HowToCook** | 菜谱查询、食材搭配 |
| 🚗 **DiDi** | 打车、出行规划 |
| 🚄 **12306** | 火车票查询、余票监控 |
| 🛒 **外卖** | 周边商家、菜品搜索 |

### Skill 规约

用 **Markdown 写一段话**，就能给 MindPet 定义新技能：

- 📝 可视化 Markdown 编辑器
- 🎯 定义触发条件 + 行为指令 + 语气约束
- ⚡ 保存即注入 system prompt，无需重启

---

## 🏗️ 项目架构

```
┌─────────────────────────────────────────────────┐
│                    入口层                         │
│    微信 Bot  │  QQ Bot  │  桌面客户端  │  ...    │
├─────────────────────────────────────────────────┤
│                  MindPet Agent                    │
│  ┌──────────┐  ┌──────────┐  ┌──────────────┐  │
│  │  MCP 管理 │  │ Skill 引擎│  │  情感分析     │  │
│  └──────────┘  └──────────┘  └──────────────┘  │
│  ┌──────────┐  ┌──────────┐  ┌──────────────┐  │
│  │  RPA 自动化│ │ Office 文档│ │  工具编排     │  │
│  └──────────┘  └──────────┘  └──────────────┘  │
├─────────────────────────────────────────────────┤
│                    记忆层                         │
│  ┌──────────┐  ┌──────────┐  ┌──────────────┐  │
│  │PG 长期记忆│  │Redis 短期│  │  知识图谱     │  │
│  │ +pgvector │  │  上下文  │  │  Neo4j/A Conf│  │
│  └──────────┘  └──────────┘  └──────────────┘  │
└─────────────────────────────────────────────────┘
```

### 技术栈

| 层 | 技术 |
|---|------|
| **桌面客户端** | Electron 39 · React 19 · TypeScript 5.9 · Vite 7 · Pixi.js (Live2D) |
| **后端服务** | Spring Boot 3.3 · Java 21 · Spring AI 1.0 |
| **向量存储** | PostgreSQL 14+ · pgvector |
| **缓存** | Redis 7 |
| **LLM** | OpenAI 兼容协议（豆包 / DeepSeek / 任意兼容 API） |
| **Embedding** | BGE-M3 (Ollama) / 豆包 Embedding |

---

## 🚀 快速开始

### 前置依赖

| 依赖 | 版本要求 | 说明 |
|------|---------|------|
| **JDK** | 21+ | 后端运行环境 |
| **Maven** | 3.8+ | 后端构建 |
| **Node.js** | 20+ | 前端运行环境 |
| **PostgreSQL** | 14+ | 需安装 `pgvector` 扩展 |
| **Redis** | 7+ | 短期记忆与缓存 |
| **Ollama** | (可选) | 本地 Embedding 模型 |

### 1. 初始化数据库

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS long_term_memory (
    id              BIGSERIAL PRIMARY KEY,
    user_id         VARCHAR(64)  NOT NULL,
    content         TEXT         NOT NULL,
    importance      DOUBLE PRECISION DEFAULT 0.5,
    embedding       vector(1024),
    metadata        JSONB        DEFAULT '{}',
    created_at      TIMESTAMP    DEFAULT now(),
    last_accessed   TIMESTAMP    DEFAULT now(),
    access_count    INTEGER      DEFAULT 0
);

CREATE INDEX ON long_term_memory USING ivfflat (embedding vector_cosine_ops);
```

### 2. 配置后端

复制配置模板并修改：

```bash
cd MindPet-java
cp src/main/resources/application-template.yml src/main/resources/application.yml
```

编辑 `application.yml`，填入你的 API Key：

```yaml
spring:
  ai:
    openai:
      api-key: your-api-key-here        # LLM API Key
      base-url: https://ark.cn-beijing.volces.com/api/v3
      chat:
        model: doubao-seed-1-8-251228
  datasource:
    url: jdbc:postgresql://localhost:5432/mindpet
    username: postgres
    password: your-db-password
  data:
    redis:
      host: localhost
      port: 6379

app:
  embedding:
    use-ollama: true                     # 使用本地 Ollama Embedding
    ollama:
      base-url: http://localhost:11434
      model: bge-m3
```

### 3. 启动后端

```bash
cd MindPet-java
mvn spring-boot:run
```

### 4. 启动桌面客户端

```bash
cd MindPet
npm install
npm run dev
```

### 5. 启动微信 Bot（可选）

```bash
# Windows
start_bot.bat

# 或手动
cd MindPet-java
java -jar target/weather-wechat-bot-1.0.0.jar --mode=bot
```

---

## 📁 项目结构

```
MINDPET/
├── MindPet/                  # Electron 桌面客户端
│   ├── src/
│   │   ├── main/             # 主进程
│   │   │   ├── tools/        # 工具系统 (MCP / Office / RPA / Skill)
│   │   │   ├── rpa/          # RPA 自动化引擎
│   │   │   └── security/     # 安全与凭据管理
│   │   └── renderer/         # 渲染进程 (React)
│   │       └── src/
│   │           ├── rpa/      # RPA 可视化编辑器
│   │           └── components/
│   └── package.json
├── MindPet-java/             # Spring Boot 后端
│   ├── src/main/java/.../
│   │   ├── service/          # 核心服务
│   │   │   ├── PgVectorMemoryService  # 长期记忆
│   │   │   ├── MemoryCuratorService   # 记忆馆长
│   │   │   ├── EmotionService         # 情感分析
│   │   │   ├── KnowledgeGraphService  # 知识图谱
│   │   │   └── McpManager            # MCP 管理器
│   │   ├── tool/             # LLM 工具实现
│   │   └── controller/       # REST API
│   └── pom.xml
├── start_bot.bat             # Windows 一键启动脚本
└── README.md
```

---

## 🔮 路线图

| 阶段 | 内容 |
|------|------|
| **近期** | 多模态记忆：图片、语音、文件被整理成带时间与来源的事件 |
| **中期** | 主动陪伴 + 多端连续：结合情绪趋势主动提醒，跨设备无感切换 |
| **远期** | 开放生态：Skill、MCP、RPA 工作流可复用和社区分享 |

---

## 📄 许可证

本项目采用 [MIT License](LICENSE) 开源。

---

<p align="center">
  <sub>Made with ❤️ by fyufvcg · 个人项目</sub>
</p>
