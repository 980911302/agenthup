# 若依 AI 后端 Dockerfile
# Build context: 仓库根目录(需要 COPY ruoyi-admin/target/ruoyi-admin.jar)
# 构建示例:
#   docker build -f ruoyi-admin/Dockerfile -t ruoyi-ai-backend:latest .

FROM eclipse-temurin:17-jre-jammy

LABEL maintainer="ruoyi-ai-agent"

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom" \
    SPRING_PROFILES_ACTIVE="druid,prod"

WORKDIR /app

# bash、文件处理和截图工具均由独立 MCP 节点执行；主应用镜像只保留 Java 运行时。

# Spring Boot fat jar(由 mvn package 在 ruoyi-admin/target/ 下产出)
COPY ruoyi-admin/target/ruoyi-admin.jar /app/app.jar

EXPOSE 8080

# 用 sh -c 包裹,允许通过环境变量覆盖 JVM 参数和 profile
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar --spring.profiles.active=${SPRING_PROFILES_ACTIVE:-druid,prod}"]
