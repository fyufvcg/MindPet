# MindPet 桌面端

Electron + React 桌面应用。仓库总览和整套开发环境说明见 [根目录 README](../README.md)。

## 环境要求

- Node.js 20+ 和 npm
- JDK 21 和 Maven：开发时构建并运行本地 Java 后端

## 开发

Windows 可从仓库根目录运行 `XiaoqingDesktop.bat` 启动桌面端和后端。

也可先构建后端，再在本目录安装依赖并启动：

```powershell
cd ..\MindPet-java
mvn -DskipTests clean package
cd ..\MindPet
npm ci
npm run dev
```

`npm run dev` 使用本地后端 JAR；默认服务地址为 `http://127.0.0.1:8080`。源码启动时，后端会由 Electron 管理。

## 构建

```powershell
npm run build:win
npm run build:mac
npm run build:linux
```

这些命令会先构建后端并准备打包运行时，再构建对应平台的 Electron 安装包或应用。输出在 `release/`。

桌面数据保存在 Electron 用户数据目录；设置 `USER_DATA_PATH` 可更改该目录。数据库位于其 `backend/mindpet.db` 下。
