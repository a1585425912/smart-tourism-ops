# 多业态融合智慧文旅管控系统

面向冰雪大世界、太阳岛、中央大街等景区的“智能运营 + 业态融合”管控平台：统一接入客流、视频点位、网评、巡检和业态经营数据，在二维/三维地图上做实时态势展示，并用 AI 运营助手（DeepSeek / 任意 OpenAI 兼容模型）完成问答、网评分析、运营日报和营销方案。

> 除地图、气象、节假日外，系统中的客流、网评、经营、团队、画像、视频画面都是**仿真演示数据**，界面上有明确标注。未对接任何景区的真实系统。

## 快速开始

```bash
cp .env.example .env
docker compose up -d --build
```

打开 `http://localhost:8080`。不使用 Docker 的方式见 [docs/deploy.md](docs/deploy.md)。

演示账号的密码统一为 `.env` 里的 `DEMO_PASSWORD`（默认 `Snow@2026demo`）：

| 账号 | 角色 | 数据范围 |
|---|---|---|
| `admin` | 集团管理员 | 全部景区 |
| `bxdsj_admin` | 景区管理员 | 冰雪大世界 |
| `tyd_admin` | 景区管理员 | 太阳岛 |
| `operator` | 运营人员 | 全部景区 |
| `inspector1` / `inspector2` | 巡检人员 | 指定景区 |
| `reviewer` | 复核人员 | 三个景区 |
| `viewer` | 只读查看者 | 冰雪大世界 |

演示环境默认启用**回放时钟**：业务时间从 2026-02-18 14:30（春节假期、冰雪季）开始走时，界面顶部会显示“演示回放”。

## 完整历史演示与本地网关

`DEMO_HISTORY_FROM` 控制历史起点，`DEMO_REPLAY_AT` 控制当前业务时间。例如从 `2025-11-01` 起，回放到 `2026-10-13T21:43:00+08:00`，启动时会补齐三个景区的客流、区域热度、排队、逐日经营、旅行社团队、月度画像、游客评价及区域早晚班巡检。仿真遵守开放季、周末及节假日差异；闭园期间客流为零，保留维保巡检与合作酒店经营。生成步骤保留已有记录并按业务键避免重复。

后台顶部可选“全局（全部授权景区）”查看跨景区数据，权限仍由服务端校验。运营日报列表默认展示每个景区每天的最新版本，详情保留历史版本；行动计划包含执行时段、责任岗位、资源安排、目标及复盘方法。

使用宿主机已有 OpenAI 兼容网关时，在私有 `.env` 中设置 `AI_PROVIDER=deepseek`、`AI_BASE_URL=http://host.docker.internal:3000/v1`、`AI_API_KEY` 和实际 `AI_MODEL` 后重新创建应用容器。不要提交密钥。

历史 AI 内容生成使用项目已有私有 `backend/config/application.yml` 中的 `snow.ai` 网关配置（宿主机可访问地址），需要 Python 和 PyYAML：

```bash
python -X utf8 scripts/data/complete_history_ai.py reviews
python -X utf8 scripts/data/complete_history_ai.py reports
python -X utf8 scripts/data/complete_history_ai.py marketing
python -X utf8 scripts/data/complete_history_ai.py reports --refresh-current
```

此批处理面向 Docker 容器 `snowworld-db-1` 中 `.env` 的 `SNOW_DB_NAME` 演示库，历史起点为 `2025-11-01`，截止日期随当前业务回放时间；先启动并等待基础数据生成完成。模型结果缓存于被 Git 忽略的 `runtime/history-ai`，失败后重新执行对应阶段可继续完成；人工修正的评价不覆盖，日报保存新版本，营销方案保持内部草稿。最后一条命令按当前数据快照生成当日新版本，适合客流持续更新后重新制定行动计划。

## 功能与赛题对应

| 赛题要求 | 实现 |
|---|---|
| 关键点位监控 | 设备目录、重点点位视频墙、短期授权播放会话；离线/无权限/不兼容分别提示（演示画面） |
| 客流数据、电子围栏 | 入园/出园/在园/区域人数分口径统计；地图绘制与编辑围栏（PostGIS 校验）；区域热度 |
| 排队时长、出游预估排队 | 排队人数 ÷ 服务能力的估计值与设备实测值分开展示，并按小时预测未来等待时间 |
| 旅行社与团队数据 | 旅行社、团队、入园人数、导游信息（脱敏），按旅行社/客源地/日期统计 |
| 酒店、餐饮、门店业态整合 | 业态单元与逐日经营数据，客流—业态联动相关性分析 |
| 游客画像 | 客源地、年龄、性别、渠道、停留时长，保留“未知”分类并标注样本覆盖率 |
| 三维可视化 | 指挥大屏 MapLibre 三维：基于 OSM 建筑轮廓拉伸，区域负荷柱体（标注为示意，非实景数字孪生） |
| AI 预测与资源配置 | 岭回归日客流预测（星期/节假日/天气/寒假等特征），时间顺序回测，对比“上周同日”基线；小时与区域峰值、人员配置建议 |
| 网评舆情 AI 分析 | CSV 导入、自动脱敏、AI 情感与主题分类（附原文证据片段）、人工修正、热点问题汇总 |
| 日常巡检 | 派单→执行→拍照→提交→整改→复核的闭环状态机，移动端 H5 |
| 业态融合营销 | AI 生成套票与营销方案草稿（客群依据、渠道、KPI、风险），仅内部草稿 |

## 技术架构

- 前端：Vue 3 + TypeScript + Vite + Element Plus + ECharts + OpenLayers + MapLibre GL
- 后端：Java 21 + Spring Boot 4 + Spring Security（会话 + CSRF）+ MyBatis + Flyway
- 数据：PostgreSQL 17 + PostGIS（空间围栏、区域判定）
- AI：后端 Agent 编排模块，模型只能调用受控的只读业务工具；景区范围由服务端注入并在每次工具调用时重新校验；输出中的数字与来源 ID 必须能对上工具返回的数据
- 后台任务：数据库任务表 + 租约 + 心跳，支持重试、取消、重启恢复

更多文档：

- [docs/api-contract.md](docs/api-contract.md)：接口契约
- [docs/deploy.md](docs/deploy.md)：部署与运维
- [docs/data-sources.md](docs/data-sources.md)：数据来源与许可
- [docs/项目需求与技术实施分析报告.md](docs/项目需求与技术实施分析报告.md)：需求分析基线

## 目录

```
backend/    Spring Boot 服务（auth scenic flow forecast review inspection agent report marketing job demo …）
frontend/   Vue 应用（/admin 运营后台、/screen 指挥大屏、/m 移动巡检）
data/       开放数据：OSM 地图要素、哈尔滨逐日气象、法定节假日
scripts/    便携运行环境与启动脚本、OSM 数据抓取脚本
docs/       需求、接口、部署、数据来源文档
```

冬季展示使用独立快照库 `snow_world_showcase`，`.env` 设置 `SNOW_DB_NAME=snow_world_showcase` 并将 `DEMO_REPLAY_AT` 设在二月营业时段；后台、大屏、预测及任务展示共用业务回放时间。完整历史保留在 `snow_world`，切回该库并将回放日期设回十月即可恢复全年数据视图。任务执行租约仍使用真实运行时间，展示时间转换为业务时间。
