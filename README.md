# MindPet

MindPet 是面向个人学习与项目实践的桌面 AI 助手。产品主线是跨会话长期记忆：记录来源、时间和事实版本，通过语义、关键词及关系线索检索相关证据，并提供来源查看与部分记忆管理能力。Agent 和本地工具用于扩展日常任务处理能力。

## 能力概览

- **长期记忆**：支持记忆写入、检索、事实更新、历史状态管理与来源回查；SQLite 和向量索引优先保存在本机。
- **Agent 任务**：Java 后端基于 Spring AI 支持多轮工具调用、按需发现工具、多步任务、进度记录、取消和中断后恢复；任务记录页支持查看证据、补充要求和继续执行。
- **能力加载**：Desktop 与 Java 基础工具常驻；内置和用户安装的 Skill 统一登记，简介常驻、正文与资源按需加载；外部 MCP 通过聊天 `/` 选择加入当前任务。
- **本地操作**：可调用已接入的文件、浏览器、桌面、Office、MCP、Skills 和 RPA 能力；实际操作范围受工具配置、运行环境与权限确认约束。
- **自动上下文**：后端按 Token 预算管理历史、任务检查点、工具结果与 Skill 正文，支持历史回读、摘要异常降级和带来源的任务约束；独立于长期记忆，不再要求设置对话轮数。详见 [上下文管理](MindPet-java/docs/context-management.md)。
- **定时任务**：SQLite 持久化计划和执行记录，复用同一 Agent 执行链路；应用运行期间后台调度，失败或中断后暂停并等待核对。
- **多入口**：Electron 桌面端提供对话、记忆与工具管理；微信和 QQ 可接入共享后端。
- **模型选择**：LLM 与 Embedding 可按配置使用本地或兼容的远程服务。具体数据流取决于模型、消息平台和工具配置。

联网搜索遵循知识优先原则：优先依据已有知识；只有问题涉及最新或易变信息，或用户明确要求联网核实、查找来源时才搜索。空结果与调用错误分开处理，并在信息不足时说明限制后尽力回答。

工具以通用办公和本地操作为主：文件、终端、电脑控制、办公文档、邮件、计算、搜索和自动化。天气、米家、点餐、打车、动漫等专用工具不再向模型开放。天气预报等时效信息使用通用搜索；单个工具的范围不代表助手的整体能力范围。用户自行配置的外部 MCP 仍可通过 `/` 选择使用，不受已移除内置专用工具的限制。完整工具名称及移除清单见[工具清单](MindPet-java/docs/tool-inventory.md)。

## 仓库结构

- `MindPet/`：Electron、React、TypeScript 桌面应用及本地工具桥接。
- `MindPet-java/`：Java 后端、Spring AI 模型与 Agent 执行能力、MCP 接入及 SQLite 存储。
- `XiaoqingDesktop.bat`：Windows 源码开发启动脚本，会构建后端并启动 Electron。
- `scripts/check.bat`：SQLite 存储检查脚本。
- `LICENSE`：MIT License。

## 环境要求

普通安装用户请先阅读 [安装与使用指南](docs/user-guide.zh-CN.md)，安装包已内置 Java 运行时；下列要求针对源码开发。

- JDK 21 和 Maven
- Node.js 20+ 和 npm
- 可用的本地或远程 LLM 服务；Embedding 可使用本地 Ollama 或按配置使用远程服务

## 开发启动

Windows 可在仓库根目录运行：

```bat
XiaoqingDesktop.bat
```

脚本会构建 Java 后端；若 `MindPet/node_modules` 不存在，则安装桌面端依赖，之后启动 Electron。也可手动分步运行：

```powershell
cd MindPet-java
mvn -DskipTests clean package
cd ..\MindPet
npm ci
npm run dev
```

联网搜索可选接入 Tavily 或 Brave 的结构化搜索 API；未配置时使用浏览器搜索，摘要足够时直接回答，按需读取原文。配置方式见 [Agent 执行说明](MindPet-java/docs/agent-execution.md#联网原则)。

首次使用时，在应用设置中分别配置对话 LLM 和长期记忆的 Embedding。对话模型填写服务商、API Key、模型名，自定义服务还需填写 Base URL；保存并同步后，可单独测试连接。Embedding 可选择“强制云端”并填写豆包方舟的 Embedding Key，无需安装 Ollama；当前云端向量接口为豆包接口，其他平台的 Key 不能直接替用。选择本地 Embedding 时，才需要安装 Ollama 并运行 `ollama pull bge-m3`。

模型密钥由 Electron 使用系统加密能力保存在本机，Java 运行时只在内存中使用密钥，并持久化不含密钥的配置。界面分别展示配置保存、后端同步和实际连接检测结果；Embedding 需要真实返回有效的 1024 维向量才显示可用。更换对话服务商或服务地址后，需要重新填写该服务的密钥。

## 构建安装包

在 `MindPet/` 目录中按目标平台执行：

```powershell
npm run build:win
npm run build:mac
npm run build:linux
```

构建流程会准备 Java 后端与运行时，再生成 Electron 安装包或应用，产物位于 `MindPet/release/`。发布前应在干净设备上检查安装、首次启动、模型连接、升级和卸载行为。

## 数据与运行边界

桌面端 SQLite 数据库默认位于 Electron 用户数据目录下的 `backend/mindpet.db`。桌面启动时将数据目录传给本地后端；独立运行后端且未设置数据路径时，默认使用用户目录下的 `.mindpet/mindpet.db`。可通过 `USER_DATA_PATH` 指定桌面端数据目录。

应用退出后不会继续操作电脑。中断的 Agent 任务可在任务记录页或原会话中显式恢复；恢复时会检查结果未知的操作，不会自动重放这些写操作。取消不能撤销已经完成的外部动作。已完成的执行记录与工具输出默认保留 90 天，可通过 `agent.completed-retention-days` 配置，设为 0 或负数关闭自动清理；会话历史与长期记忆不受此配置影响。

## 技术栈

- 桌面端：Electron 39、React 19、TypeScript 5.9
- 后端：Java 21、Spring Boot 3.5.16、Spring AI 1.1.8
- 存储：SQLite JDBC、sqlite-vec；向量检索提供 Java 回退实现
- 工具接入：MCP、Skills、基础 RPA 和 Electron 本地工具桥接

## 文档

- [桌面端说明](MindPet/README.md)
- [Java 后端说明](MindPet-java/README.md)
- [Agent 执行、配置、任务恢复与 API](MindPet-java/docs/agent-execution.md)
- [Agent 验证记录与验收边界](MindPet-java/docs/agent-validation.md)
- [通用工具清单与专用工具移除范围](MindPet-java/docs/tool-inventory.md)
- [Skill 渐进式披露、Desktop 常驻与 MCP 任务选择](MindPet-java/docs/capability-framework.md)
