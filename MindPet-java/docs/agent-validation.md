# Agent 改造验证记录

日期：2026-10-15。原始版本基线：`6c798a5`。

## 执行链路验证

新增测试通过真实 Spring AI `ChatClient`、`ToolCallAdvisor` 与默认 `ToolCallingManager` 执行，使用脚本模型、临时 SQLite 与本机 HTTP MCP 测试服务。不会调用外部模型或操作真实桌面。

本次结果：`AgentTaskServiceTest` 14 项、`AgentMcpBridgeTest` 2 项全部通过；Electron 主进程与渲染进程 TypeScript 检查通过，`electron-vite build` 生产构建通过。`git diff --check` 通过。

覆盖：同工具连续五次操作、同步和流式工具消息配对、执行中发现新工具、相同失败参数抑制、输出分页、持久化脱敏、进程重启恢复、未确定写操作不重放、批次内已知结果恢复、活动工具取消、路由阶段取消、图片实际进入下一轮模型、整轮预算、上下文压缩、等待输入状态与跨轮用量累计。

命令：

```powershell
cd MindPet-java
mvn "-Dtest=AgentTaskServiceTest,AgentMcpBridgeTest" test
cd ..\MindPet
npm run typecheck
npm exec electron-vite build
```

## 后端打包与启动

当前运行中的 Java 进程占用默认 `target/weather-wechat-bot-1.0.0.jar`，普通打包在 Spring Boot 重命名该文件时失败。本次改用独立构建目录，已成功生成 `target/agent-package/mindpet-agent.jar`（75,996,301 字节），确认包含 Spring Boot 启动器、Agent 类和 Spring AI 1.1.8 依赖。没有启动第二个后端进程。

独立构建使用 `target/agent-package/pom.xml`（从项目 POM 复制，仅调整源码、资源与产物绝对路径），命令为：

```powershell
mvn -q -f target/agent-package/pom.xml "-Dmaven.test.skip=true" package
```

默认路径的这次打包未完成，不能将其当作已交付的可运行包。下次启动可使用根目录 `XiaoqingDesktop.bat`，它会先停止占用默认 JAR 的旧后端并重新构建。若手动使用独立 JAR，应先正常退出旧后端，并沿用原来的 `--mode=bot`、`application-desktop.yml` 和 SQLite 数据路径配置，避免启动到另一个数据库。当前运行的应用仍需重启才能加载改造。

## 全量后端回归的已有失败

执行 `mvn test` 时，发现以下测试失败：

- `SensitivePersistenceGuardTest`：3 个断言失败、1 个错误。
- `V34ProductionSafetyRegressionTest`：3 个断言失败、1 个错误。

从 `HEAD` 导出的干净源码副本单独运行这两组测试，复现相同的 6 个断言失败和 2 个错误。断言涉及 `[REDACTED_ACCOUNT]` 与 `[REDACTED_SECRET]` 的差异，错误涉及脱敏后的 SQL 字面量。对应记忆与脱敏实现没有在本次任务中修改。

完整日志保存在 `target/agent-regression.log`，原版复测日志保存在 `target/agent-baseline-regression.log`。这些文件是本机验证产物，不应作为版本控制文件提交。

## 尚未验证的范围

未进行真实模型服务、真实鼠标键盘操作、真实 Office 窗口与长时间联网任务的端到端验收。生产构建通过说明代码可以打包，不等于这些外部环境已经验收。应用需要重新构建并重启后端和 Electron 才会加载新的执行链路。
