# ============================================================
# MindPet 后端镜像
# ------------------------------------------------------------
# 构建前置条件：先把 fat jar 复制到本目录
#     scripts\build-backend.bat
#   或手动：
#     cd MindPet-java && mvn clean package -DskipTests
#     copy target\weather-wechat-bot-1.0.0.jar docker\backend\
#
# 构建（在仓库根目录）：
#     docker compose -f docker\compose.yaml build backend
#
# 说明：不以仓库根为构建上下文，避免把 1.2GB 的 node_modules
#       上传给 Docker daemon。
# ============================================================
FROM eclipse-temurin:21-jre

WORKDIR /app

# 时区：后端大量使用 LocalDateTime.now()，容器默认 UTC 会导致
# "今天是几号"、天气推送时间、事件时间轴全部偏移 8 小时。
ENV TZ=Asia/Shanghai

# fat jar 内已包含 /BOOT-INF/classes/application.yml（不含任何密钥）。
# 敏感配置与容器内地址一律通过 compose 的 environment 覆盖。
COPY weather-wechat-bot-1.0.0.jar app.jar

# 运行时可写文件（llm-dynamic-config.json / skills-config.json 等）
# 均写在 WORKDIR 下（见 DynamicLlmConfig / SkillStore 的相对路径），
# compose 会把卷挂到 /app 以持久化这些配置。
# 注意：卷挂载在容器创建时会把镜像内 /app 的内容复制进空卷，因此 app.jar 不会丢。
EXPOSE 8080

# 容器内存上限通过 JAVA_TOOL_OPTIONS 注入（见 compose）。
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
