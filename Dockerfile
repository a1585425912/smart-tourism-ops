# 多阶段构建: 前端 → 后端 → 运行镜像。在仓库根目录执行: docker compose build
# 国内网络可通过构建参数替换镜像源, 见 docs/deploy.md

FROM node:24-alpine AS web
ARG NPM_REGISTRY=https://registry.npmjs.org/
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm config set registry ${NPM_REGISTRY} && npm ci --no-audit --no-fund
COPY frontend/ ./
COPY data/osm/ ./public/geo/
RUN npx vite build

FROM maven:3.9-eclipse-temurin-21 AS api
WORKDIR /src/backend
COPY backend/pom.xml ./
RUN mvn -q -B dependency:go-offline -Dmaven.repo.local=/m2 || true
COPY backend/src ./src
RUN mvn -q -B -DskipTests -Dmaven.repo.local=/m2 package

FROM eclipse-temurin:21-jre
ENV TZ=Asia/Shanghai \
    SNOW_DATA_DIR=/app/data \
    SNOW_STORAGE_DIR=/app/storage \
    SNOW_FRONTEND_DIR=/app/frontend \
    JAVA_OPTS="-Xms256m -Xmx1g"
WORKDIR /app
RUN groupadd -r snow && useradd -r -g snow snow && mkdir -p /app/storage && chown snow:snow /app/storage
COPY --from=api /src/backend/target/snow-world-backend-0.1.0.jar /app/app.jar
COPY --from=web /src/frontend/dist /app/frontend
COPY data/ /app/data/
USER snow
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=120s --retries=5 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /actuator/health HTTP/1.0\r\n\r\n" >&3 && grep -q UP <&3' || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar --snow.frontend-dir=$SNOW_FRONTEND_DIR"]
