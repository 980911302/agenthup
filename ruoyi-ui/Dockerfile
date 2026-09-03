# 若依 AI 前端 Dockerfile(多阶段)
# Build context: ruoyi-ui/(在该目录下执行 docker build)
# 构建示例:
#   cd ruoyi-ui && docker build -t ruoyi-ai-frontend:latest .

# ===== 第一阶段:用 node 构建 =====
FROM node:20-alpine AS builder

WORKDIR /build

# 优先拷 package 文件装依赖(利用 Docker 缓存)
COPY package*.json ./
RUN npm ci --no-audit --no-fund

# 拷源码并构建
COPY . .
RUN npm run build:prod

# ===== 第二阶段:运行时 nginx =====
# 部署时通过 volume 挂载 nginx.conf 覆盖 /etc/nginx/conf.d/default.conf(见 docker-compose.deploy.yml)
# 所以镜像内不内置 nginx.conf,保持简洁
FROM nginx:1.27-alpine

LABEL maintainer="ruoyi-ai-agent"

# 时区
ENV TZ=Asia/Shanghai

# 拷构建产物
COPY --from=builder /build/dist /usr/share/nginx/html

EXPOSE 80

# 镜像自带 ENTRYPOINT,这里只给默认 CMD
CMD ["nginx", "-g", "daemon off;"]
