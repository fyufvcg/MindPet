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
