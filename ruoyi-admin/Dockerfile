# 若依 AI 后端 Dockerfile
# Build context: 仓库根目录(需要 COPY ruoyi-admin/target/ruoyi-admin.jar)
# 构建示例:
#   docker build -f ruoyi-admin/Dockerfile -t ruoyi-ai-backend:latest .

FROM eclipse-temurin:17-jre-jammy

LABEL maintainer="ruoyi-ai-agent"

# 构建期代理(可选):宿主机有 mihomo/cpa-proxy 时传 http_proxy/https_proxy 加速下载
ARG http_proxy=""
ARG https_proxy=""
ARG no_proxy="localhost,127.0.0.1"

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom" \
    SPRING_PROFILES_ACTIVE="druid,prod"

WORKDIR /app

# 固定 Playwright 浏览器下载目录,便于软链(默认在 /root/.cache,路径不可控)
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright

# 沙箱基线:Node LTS + xz + 无头 Chromium(captureScreenshot / 避免每会话现场装 Node)
# Ubuntu Jammy 的 apt 源没有 chromium 包(官方已转向 snap,容器内不可用),
# 因此改用 Playwright 下载官方 Chromium 构建,并软链到 /usr/local/bin/chromium 供进程调用。
ENV http_proxy="${http_proxy}" \
    https_proxy="${https_proxy}" \
    no_proxy="${no_proxy}" \
    HTTP_PROXY="${http_proxy}" \
    HTTPS_PROXY="${https_proxy}" \
    NO_PROXY="${no_proxy}"
RUN sed -i 's|http://ports.ubuntu.com/ubuntu-ports|https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports|g' /etc/apt/sources.list \
    && http_proxy= https_proxy= HTTP_PROXY= HTTPS_PROXY= apt-get update \
    && http_proxy= https_proxy= HTTP_PROXY= HTTPS_PROXY= apt-get install -y --no-install-recommends \
        curl ca-certificates xz-utils \
        fonts-liberation fonts-noto-cjk \
        python3-pip python-is-python3 \
        tesseract-ocr tesseract-ocr-chi-sim \
        ffmpeg \
    && curl -fsSL --proxy "${https_proxy:-}" https://deb.nodesource.com/setup_20.x | bash - \
    && http_proxy= https_proxy= HTTP_PROXY= HTTPS_PROXY= apt-get install -y --no-install-recommends nodejs \
    && npm config set registry https://registry.npmmirror.com \
    && npm config set fetch-retries 2 \
    && npm config set fetch-retry-maxtimeout 60000 \
    && npx -y playwright@1.49.1 install --with-deps chromium \
    && ln -sf /ms-playwright/chromium-*/chrome-linux/chrome /usr/local/bin/chromium \
    && pip3 install --no-cache-dir \
        "python-docx==1.1.2" \
        "openpyxl==3.1.5" \
        "python-pptx==1.0.2" \
        "pymupdf==1.25.2" \
        "pdfplumber==0.11.4" \
        "pypdf==5.1.0" \
        "reportlab==4.2.5" \
        "markitdown==0.1.1" \
        "pytesseract==0.3.13" \
        "cryptography==43.0.3" \
    && rm -rf /var/lib/apt/lists/*

# npm 镜像源。上面给 apt 配了清华源,npm 却一直走默认 registry.npmjs.org ——
# 智能体在工作区跑 npm install 时会卡到跑满 bash 工具的超时(实测 300s / 600s
# 两次都是撞满超时退出,等于白等,它还会 rm -rf node_modules 重来一遍)。
RUN npm config set registry https://registry.npmmirror.com \
    && npm config set fetch-retries 2 \
    && npm config set fetch-retry-maxtimeout 60000

ENV http_proxy="" \
    https_proxy="" \
    HTTP_PROXY="" \
    HTTPS_PROXY=""

ENV CHROMIUM_PATH=/usr/local/bin/chromium \
    PUPPETEER_SKIP_CHROMIUM_DOWNLOAD=true

# Spring Boot fat jar(由 mvn package 在 ruoyi-admin/target/ 下产出)
COPY ruoyi-admin/target/ruoyi-admin.jar /app/app.jar

EXPOSE 8080

# 用 sh -c 包裹,允许通过环境变量覆盖 JVM 参数和 profile
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar --spring.profiles.active=${SPRING_PROFILES_ACTIVE:-druid,prod}"]
