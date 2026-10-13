# 演示数据库快照

`snow_world_demo.sql.gz` 是系统首次启动后自动生成的完整演示数据（`pg_dump`，不含属主与权限），业务时钟为 2026-02-18 14:30 回放场景。

通常**不需要**手动导入：应用启动时（`SNOW_DEMO_SEED=true`）会自动生成同样的数据。这个快照用于：

- 在不启动应用的情况下直接查看或分析数据；
- 需要与截图完全一致的数据状态时。

数据性质见 [docs/data-sources.md](../../docs/data-sources.md)：地图、气象、节假日为真实开放数据；客流、经营、团队、画像为规则仿真；网评文本由大模型生成。均不含真实个人信息。演示账号密码以哈希形式保存。

## 导入

```bash
# Docker Compose 部署（导入到空库，导入后启动应用时会跳过演示数据生成）
docker compose up -d db
gzip -dc data/demo/snow_world_demo.sql.gz | docker compose exec -T db psql -U snow -d snow_world
docker compose up -d app
```

导入后演示账号密码为生成快照时的 `DEMO_PASSWORD`（默认 `Snow@2026demo`）。
