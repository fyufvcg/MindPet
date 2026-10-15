# MindPet

MindPet 是面向个人学习与项目实践的桌面 AI 助手。产品主线是跨会话长期记忆：记录来源、时间和事实版本，通过语义、关键词及关系线索检索相关证据，并提供来源查看与部分记忆管理能力。Agent 和本地工具用于扩展日常任务处理能力。

## 能力概览

- **长期记忆**：支持记忆写入、检索、事实更新、历史状态管理与来源回查；SQLite 和向量索引优先保存在本机。
- **Agent 任务**：Java 后端基于 Spring AI 支持多轮工具调用、按需发现工具、多步任务、进度记录、取消和中断后恢复。
- **本地操作**：可调用已接入的文件、浏览器、桌面、Office、MCP、Skills 和 RPA 能力；实际操作范围受工具配置、运行环境与权限确认约束。
- **多入口**：Electron 桌面端提供对话、记忆与工具管理；微信和 QQ 可接入共享后端。
- **模型选择**：LLM 与 Embedding 可按配置使用本地或兼容的远程服务。具体数据流取决于模型、消息平台和工具配置。

联网搜索遵循知识优先原则：优先依据已有知识；只有问题涉及最新或易变信息，或用户明确要求联网核实、查找来源时才搜索。空结果与调用错误分开处理，并在信息不足时说明限制后尽力回答。

## 仓库结构

- `MindPet/`：Electron、React、TypeScript 桌面应用及本地工具桥接。
- `MindPet-java/`：Java 后端、Spring AI 模型与 Agent 执行能力、MCP 接入及 SQLite 存储。
- `XiaoqingDesktop.bat`：Windows 源码开发启动脚本，会构建后端并启动 Electron。
- `scripts/check.bat`：SQLite 存储检查脚本。
- `LICENSE`：MIT License。

## 环境要求

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

首次使用时，在应用设置中配置 LLM 服务。配置模板 `MindPet-java/src/main/resources/application-template.yml` 默认使用 Ollama 的 `bge-m3` 作为本地 Embedding；使用本地 Embedding 前需安装 Ollama 并运行 `ollama pull bge-m3`。也可按模板配置兼容的远程 Embedding 服务。

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

应用退出后不会继续操作电脑。中断的 Agent 任务需要在原会话中显式恢复；恢复时会检查结果未知的操作，不会自动重放这些写操作。取消不能撤销已经完成的外部动作。

## 技术栈

- 桌面端：Electron 39、React 19、TypeScript 5.9
- 后端：Java 21、Spring Boot 3.5.16、Spring AI 1.1.8
- 存储：SQLite JDBC、sqlite-vec；向量检索提供 Java 回退实现
- 工具接入：MCP、Skills、基础 RPA 和 Electron 本地工具桥接

## 文档

- [桌面端说明](MindPet/README.md)
- [Java 后端说明](MindPet-java/README.md)
- [Agent 执行、配置、任务恢复与 API](MindPet-java/docs/agent-execution.md)
- [Agent 验证记录及已知测试差异](MindPet-java/docs/agent-validation.md)
