# 部署说明

本系统有两种运行方式，二者使用同一套代码和配置项。

| 方式 | 适用场景 | 依赖 |
|---|---|---|
| A. Docker Compose（推荐） | 在任意一台新机器上部署、比赛演示 | Docker 24+（含 compose 插件） |
| B. 本机便携运行时 | Windows 开发机，不装 Docker | JDK 21、Maven 3.9、Node 24 |

## A. Docker Compose

```bash
git clone <仓库地址> snow_world && cd snow_world
cp .env.example .env          # Windows: copy .env.example .env
# 按需编辑 .env（端口、演示密码、AI 配置）
docker compose up -d --build
docker compose logs -f app    # 看到 "Started SnowWorldApplication" 与演示数据生成完成即可
```

浏览器访问 `http://<主机IP>:8080`：

- 运营后台：`/admin/overview`
- 指挥大屏：`/screen`（建议 1920×1080 全屏）
- 移动巡检：`/m`（手机浏览器）
- 接口文档：`/swagger-ui.html`

首次启动会自动建表（Flyway）并生成演示数据，约需 20–40 秒。演示数据都保存在 `pgdata` 卷里，重启不会重复生成。

### Radmin VPN 远程演示

Radmin 入口默认关闭。需要开启时，主机和访问者先加入同一个 Radmin VPN 网络。在 `.env` 中填写主机的 Radmin VPN IPv4 地址和额外入口端口，例如：

```dotenv
RADMIN_BIND_IP=26.78.219.65
RADMIN_PORT=18080
```

应用端口仍为 8080；新增入口转发到同一应用，访问者打开 `http://26.78.219.65:18080`，大屏为 `/screen`。示例 IP 请替换为实际主机地址。默认绑定 127.0.0.1，仅本机可访问。

开启时执行 `docker compose -f docker-compose.yml -f docker-compose.radmin.yml up -d --no-deps app`；关闭时执行 `docker compose -f docker-compose.yml up -d --no-deps app`。Windows 防火墙若拦截连接，在管理员 PowerShell 中运行 `./scripts/open-radmin-port.ps1`；脚本仅允许 Radmin VPN 接口和 26.0.0.0/8 来源访问该端口。需要关闭防火墙规则时运行 `Remove-NetFirewallRule -Name SnowWorld-Radmin-18080`。主机的 Radmin 地址变化后，应更新 `.env`、重新创建应用容器，并重新运行防火墙脚本。

### 常用操作

```bash
docker compose ps                       # 状态（app 显示 healthy 即就绪）
docker compose restart app              # 重启应用
docker compose down                     # 停止（保留数据）
docker compose down -v                  # 停止并清空数据库与上传文件，下次启动重新生成演示数据
docker compose exec db pg_dump -U snow snow_world > backup.sql   # 备份
```

### 在无法联网或网络较慢的机器上部署

先在能联网的机器上构建好镜像，再导出、拷贝、导入：

```bash
docker compose build
docker pull postgis/postgis:17-3.5
docker save snow-world:latest postgis/postgis:17-3.5 -o snow-world-images.tar
# 拷贝 snow-world-images.tar、docker-compose.yml、.env.example 到目标机器后：
docker load -i snow-world-images.tar
docker compose up -d        # 不加 --build
```

国内网络构建时，可在 `.env` 设置 `NPM_REGISTRY=https://registry.npmmirror.com/`，并为 Docker 配置镜像加速；PostGIS 镜像可通过 `POSTGIS_IMAGE` 改为镜像仓库地址。

### AI 配置

| AI_PROVIDER | 说明 |
|---|---|
| `fake`（默认） | 本地规则模拟器，离线可用，同样走完整的工具调用与校验流程，界面标注“本地规则模拟” |
| `deepseek` | 任意 OpenAI 兼容接口（DeepSeek 官方或自建网关），需填写 `AI_BASE_URL`、`AI_API_KEY`、`AI_MODEL` |

客户端请求 `${AI_BASE_URL}/chat/completions`。示例：

```ini
# DeepSeek 官方
AI_PROVIDER=deepseek
AI_BASE_URL=https://api.deepseek.com
AI_API_KEY=sk-...
AI_MODEL=deepseek-flash

# 宿主机上的 OpenAI 兼容网关（如 New API，监听 3000 端口）
AI_PROVIDER=deepseek
AI_BASE_URL=http://host.docker.internal:3000/v1
AI_API_KEY=sk-...
AI_MODEL=deepseek-v4.1-flash
```

密钥只写在 `.env`（已被 git 忽略），不会进入镜像、前端或日志。

### 生产化注意事项

当前 compose 以演示为目标（`SPRING_PROFILES_ACTIVE=dev`，开启演示数据与回放时钟）。正式部署前需要：

1. 设置 `DEMO_SEED=false`、`DEMO_REPLAY_AT=`（留空），使用真实数据源；
2. 修改 `DB_PASSWORD` 与 `DEMO_PASSWORD`，或通过“用户与授权”页面重建账号；
3. 在前面加 Nginx/网关并启用 HTTPS（会话 Cookie 为 HttpOnly，建议同时开启 Secure）；
4. 定期备份 `pgdata` 卷，并演练恢复；
5. 按真实并发、视频路数做压测后再确定机器配置，本项目未做负载测试，不承诺性能指标。

## B. 本机便携运行时（Windows）

```powershell
powershell -ExecutionPolicy Bypass -File scripts\setup-runtime.ps1   # 只需一次：下载并初始化 PostgreSQL 17 + PostGIS 到 runtime\
powershell -ExecutionPolicy Bypass -File scripts\start.ps1           # 构建并启动，访问 http://localhost:8080
powershell -ExecutionPolicy Bypass -File scripts\stop.ps1            # 关闭数据库
```

开发模式（前端热更新）：后端在 `backend\` 下运行 `mvn spring-boot:run -Dspring-boot.run.profiles=dev`，前端在 `frontend\` 下运行 `npm run dev`，访问 `http://localhost:5173`。

本机私有配置（如 AI 密钥）可写在 `backend\config\application.yml`（已被 git 忽略），格式见 `snow.ai.*`。

## 测试

```bash
# 后端：需要可连接的 PostgreSQL/PostGIS 测试库（默认 localhost:55432/snow_test，可用 SNOW_TEST_DB_URL 覆盖）
cd backend && mvn test
# 前端
cd frontend && npx vue-tsc --noEmit && npx vitest run && npx vite build
```

自动化测试使用本地假模型，不会调用付费的 AI 接口。
