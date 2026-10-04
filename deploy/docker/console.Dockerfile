# 控制台静态站。生产构建不启用 MSW（main.ts 只在 DEV 里挂 mock）。
# /api 反代到集群内的 keel-server，浏览器不直连 8080。

FROM node:22-bookworm-slim AS build
WORKDIR /src
COPY console/package.json console/package-lock.json ./
RUN npm ci
COPY console/ ./
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY deploy/docker/console-nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 8080
