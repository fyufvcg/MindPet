# MindPet

MindPet 是一个桌面 AI 助手。桌面端使用 Electron、React 和 TypeScript，Java 后端提供对话、工具调用和记忆能力；本地数据使用 SQLite。LLM 和 Embedding 需要配置可用的本地或远程服务。

## 目录

- `MindPet/`：Electron 桌面端
- `MindPet-java/`：Spring Boot 后端
- `XiaoqingDesktop.bat`：Windows 源码开发启动脚本
- `scripts/check.bat`：SQLite 存储检查脚本
- `LICENSE`：MIT License

## 源码开发

需要安装 JDK 21、Maven、Node.js 20+ 和 npm。Windows 上在仓库根目录运行：

```bat
XiaoqingDesktop.bat
```

脚本会构建后端；若桌面端依赖目录不存在，会运行 `npm ci`，随后启动 Electron。也可以分步运行：

```powershell
cd MindPet-java
mvn -DskipTests clean package
cd ..\MindPet
npm ci
npm run dev
```

首次使用前，在应用设置中配置 LLM 服务。配置模板 `MindPet-java/src/main/resources/application-template.yml` 默认使用 Ollama 的 `bge-m3` 做本地 Embedding；使用本地 Embedding 时需安装 Ollama 并运行 `ollama pull bge-m3`。也可按模板改用兼容的远程 Embedding 服务。

## 构建桌面安装包

在 `MindPet` 目录执行对应平台的构建命令：

```powershell
npm run build:win
npm run build:mac
npm run build:linux
```

构建脚本会打包 Java 后端及 `jlink` 生成的运行时；输出位于 `MindPet/release/`。打包配置包含随应用提供 Java 运行时的步骤，正式发布前应在干净机器上验证安装包。

## 本地数据

桌面端把数据保存在 Electron 的用户数据目录中，数据库路径为 `<userData>/backend/mindpet.db`。打包版会先尝试在可执行文件旁使用 `data/`；无法创建该目录时使用 Electron 默认目录。设置环境变量 `USER_DATA_PATH` 可指定其他目录。独立运行后端时，SQLite 默认路径为 `~/.mindpet/mindpet.db`。

## 技术版本

- 桌面端：Electron 39、React 19、TypeScript 5.9
- 后端：Java 21、Spring Boot 3.5.16、Spring AI 1.1.8
- 存储：SQLite；向量检索使用 sqlite-vec，并提供 Java 回退实现

更多模块说明见 [桌面端 README](MindPet/README.md) 和 [后端 README](MindPet-java/README.md)。
