# MindPet Java 后端

Spring Boot 服务，为桌面端提供本地 HTTP API、LLM 调用、工具和记忆能力。整套项目说明见 [根目录 README](../README.md)。

## 环境与构建

需要 JDK 21 和 Maven。后端使用 SQLite，不需要另装数据库服务。

```powershell
mvn -DskipTests clean package
```

生成的 JAR 位于 `target/weather-wechat-bot-1.0.0.jar`。Windows 桌面开发可在仓库根目录运行 `XiaoqingDesktop.bat`；桌面端会启动本地后端，默认监听 `127.0.0.1:8080`。

## 配置与数据

- `application-desktop.yml` 提供桌面后端默认配置；桌面端可在运行时配置模型连接，也可通过环境变量设置本地参数。
- `src/main/resources/application-template.yml` 是后端配置参考模板。请勿提交包含密钥的本地配置。
- 桌面端启动时把数据目录传给后端，SQLite 数据库位于 `<userData>/backend/mindpet.db`。
- 独立运行后端且未设置数据路径时，默认数据库位置为 `~/.mindpet/mindpet.db`。

Java 依赖、版本和构建配置以 [pom.xml](pom.xml) 为准；桌面端实现位于 [MindPet](../MindPet/)。

能力框架：Desktop 与 Java 基础工具常驻，Skill 按需加载，外部 MCP 通过 `/` 加入任务。详见 [能力加载与任务框架](docs/capability-framework.md)。

会话历史与任务上下文采用独立 Token 预算和检查点，不改变长期记忆算法。配置和数据边界见 [会话上下文管理](docs/context-management.md)。

后台计划由独立 Store、Scheduler 和 Runner 管理，执行复用 Spring AI；外部 MCP 协议统一由 Electron 官方 SDK 处理。任务恢复、搜索服务和记录保留配置见 [Agent 执行说明](docs/agent-execution.md)，最新检查结果见 [验证记录](docs/agent-validation.md)。
