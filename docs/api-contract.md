# snow_world API 契约 v1

> 前后端与各模块共同遵守的接口约定。实现以本文件为准；变更接口须同步更新本文件。
> 运行时 OpenAPI 文档：`/v3/api-docs`，Swagger UI：`/swagger-ui.html`。

## 0. 通用约定

| 项 | 约定 |
|---|---|
| 前缀 | `/api/v1` |
| 认证 | 同源服务端会话，Cookie `SNOWSESSION`（HttpOnly） |
| CSRF | 首次写请求前调用 `GET /auth/csrf`，后端下发 Cookie `XSRF-TOKEN`；所有 POST/PATCH/PUT/DELETE 带请求头 `X-XSRF-TOKEN: <cookie 值>`（axios 默认行为即可） |
| 时间 | ISO-8601 带时区偏移，如 `2026-02-18T14:30:00+08:00`；业务日按 Asia/Shanghai；时间段一律左闭右开 `[start, end)` |
| 日期 | `YYYY-MM-DD` |
| 分页 | `page` 从 1 开始，`size` 默认 20、上限 200；响应 `{items, total, page, size}` |
| 金额 | 数字（精确小数）+ 单独 `currency` 字段 |
| 缺失值 | 返回 `null`，前端显示“暂无数据”，**不得转换为 0** |
| 几何 | GeoJSON 对象，WGS84 经纬度 `[lon, lat]` |

### 错误响应

非 2xx 响应体：

```json
{ "code": "SITE_FORBIDDEN", "message": "无权访问该景区数据", "traceId": "a1b2c3d4e5f60718", "details": {} }
```

| HTTP | code | 说明 |
|---|---|---|
| 400 | `BAD_REQUEST` / `VALIDATION_FAILED`（details.fields） / `RANGE_TOO_LARGE` | 参数问题 |
| 401 | `UNAUTHENTICATED` / `BAD_CREDENTIALS` | 前端跳转登录 |
| 403 | `FORBIDDEN` / `SITE_FORBIDDEN` / `CSRF_INVALID` | 无权限 |
| 404 | `NOT_FOUND` | |
| 409 | `CONFLICT`（乐观锁） / `ILLEGAL_TRANSITION` | 前端提示刷新 |
| 413 | `PAYLOAD_TOO_LARGE` | |
| 422 | `INVALID_GEOMETRY` / `IMPORT_FAILED` | |
| 429 | `QUOTA_EXCEEDED` | AI 额度 |
| 503 | `AI_UNAVAILABLE` / `VIDEO_UNAVAILABLE` | |

### DataMeta（事实数据来源元信息）

```json
{ "dataAsOf": "2026-02-18T14:00:00+08:00", "sourceIds": ["flow-demo-hbxw"], "dataMode": "DEMO", "quality": "GOOD" }
```

- `dataMode`：`REAL` / `DEMO` / `MIXED`。前端凡 `DEMO`/`MIXED` 必须显示“演示数据”标记。
- `quality`：`GOOD` / `STALE`（超过刷新周期 3 倍未更新）/ `PARTIAL`（区间内有缺口）/ `MISSING`（无数据）。
- `sourceIds` 是 `integration_source.code`，可在“接入与任务状态”页解析。

## 1. 身份、系统、用户

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/auth/csrf` | `{headerName, token}`，同时下发 Cookie |
| POST | `/auth/login` | `{username, password}` → `Me` |
| POST | `/auth/logout` | `{ok:true}` |
| GET | `/auth/me` | `Me` |
| GET | `/system/info` | 公开：`{name, now, replay, demoData, aiProvider, aiModel, videoProvider, mapAttribution}`；`now` 为业务时钟（演示回放时为回放时间），前端所有“今天/当前”以此为准 |
| GET/POST | `/users`、PATCH `/users/{id}` | 用户管理（USER_MANAGE） |
| GET | `/audit-logs?limit=100` | 审计日志（AUDIT_READ） |

```ts
interface Me { id: number; username: string; displayName: string; roles: string[]; permissions: string[];
  sites: { id: number; code: string; name: string; shortName: string }[] }
```

权限码：`SITE_READ SITE_WRITE DEVICE_READ DEVICE_WRITE VIDEO_VIEW FLOW_READ FLOW_IMPORT REVIEW_READ REVIEW_WRITE REVIEW_IMPORT INSPECTION_READ INSPECTION_MANAGE INSPECTION_EXECUTE INSPECTION_REVIEW BUSINESS_READ AI_USE AI_DRAFT_INSPECTION MARKETING_WRITE REPORT_READ REPORT_WRITE INTEGRATION_READ INTEGRATION_MANAGE USER_MANAGE AUDIT_READ`。前端据此隐藏菜单/按钮，但后端始终独立校验。

演示账号（dev profile，密码见启动说明）：`admin`（集团管理员，全部景区）、`bxdsj_admin`（冰雪大世界景区管理员）、`tyd_admin`（太阳岛景区管理员）、`operator`（运营，全部景区）、`inspector1`/`inspector2`（巡检）、`reviewer`（复核）、`viewer`（只读）。

## 2. 景区、区域、设备、视频、地图

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/scenic-sites` | 授权景区 `Site[]` |
| GET/PATCH | `/scenic-sites/{id}` | 详情/更新基础资料（SITE_WRITE） |
| GET | `/scenic-sites/{id}/zones` | `Zone[]` |
| POST | `/scenic-sites/{id}/zones` | 新增围栏 → 201 `Zone`；非法多边形 422 |
| PATCH | `/zones/{id}` | 更新（须带 `version`，冲突 409） |
| DELETE | `/zones/{id}` | 停用 → 204 |
| GET | `/devices?siteId=&type=&status=&keyPoint=` | `Device[]`（siteId 可重复传多个） |
| GET | `/devices/status-summary?siteId=` | `[{siteId, deviceType, status, cnt, latest}]` |
| POST | `/devices/{id}/playback-session` | `PlaybackSession` |
| GET | `/video/sessions/{token}/media` | 演示视频文件流（仅签发者、未过期） |
| GET | `/map/pois?siteId=&category=` | OSM 兴趣点 `[{id, siteId, osmRef, name, category, subType, lon, lat}]` |

```ts
interface Site { id; code; name; shortName; timezone; capacity: number; warnRatio: number; openTime; closeTime;
  seasonNote; description; center: GeoJSON.Point; boundary: GeoJSON.MultiPolygon | null; boundarySrc; status: 'OPEN'|'CLOSED'|'SEASONAL';
  sortOrder; areaSqm: number }
interface Zone { id; siteId; code; name; zoneType: 'AREA'|'ENTRANCE'|'ATTRACTION'|'SERVICE'|'PARKING'|'COMMERCIAL';
  boundary: GeoJSON.Polygon; capacity: number|null; warnRatio: number; countSource: string|null; remark; version: number;
  active: boolean; areaSqm: number; outsideSite: boolean; updatedAt }
// POST/PATCH body: { code?, name, zoneType, boundary: {type:'Polygon', coordinates}, capacity?, warnRatio?, countSource?, remark?, version? }
interface Device { id; siteId; zoneId; zoneName; code; name; deviceType: 'CAMERA'|'GATE'|'COUNTER'|'SENSOR'|'SCREEN';
  keyPoint: boolean; lon: number; lat: number; vendor; protocol; sourceDeviceId; streamRef;
  status: 'ONLINE'|'OFFLINE'|'FAULT'|'UNKNOWN'; statusAt; dataMode: 'REAL'|'DEMO' }
interface PlaybackSession { status: 'OK'|'OFFLINE'|'INCOMPATIBLE'; type: 'DEMO_SYNTHETIC'|'DEMO_FILE'|'HLS'|null;
  url: string|null; expiresAt; message: string; dataMode; attribution: string|null }
```

播放器须区分：无权限（403）、设备离线（status=OFFLINE）、不兼容（INCOMPATIBLE）、连接失败（请求异常/视频加载错误）。`DEMO_SYNTHETIC` 由前端绘制带“演示画面”水印的模拟画面。

底图图层为静态开放数据，前端从 `/geo/{areas|water|roads|buildings}.geojson` 加载（来源 `data/osm`，© OpenStreetMap contributors，ODbL），必须在地图角落显示署名。

## 3. 客流、排队、预测

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/flows/summary?siteId=&start=&end=` | `FlowSummary` |
| GET | `/flows/trend?siteId=&zoneId=&metric=&granularity=&start=&end=` | `FlowTrend`；HOUR ≤31 天，DAY ≤400 天 |
| GET | `/flows/zones?siteId=` | `ZoneLoad[]` 区域热度 |
| POST | `/imports/flows` | multipart：`file`(CSV)、`siteId` → `ImportResult` |
| GET | `/queues?siteId=` | `QueueStatus[]` |
| GET | `/forecasts/daily?siteId=&days=7` | `DailyForecast` |
| GET | `/forecasts/hourly?siteId=&date=` | `HourlyForecast`（含各点位峰值与资源建议） |
| GET | `/forecasts/backtests?siteId=` | 回测记录 |
| POST | `/forecasts/refresh?siteId=` | 202 `{jobId}` 重新训练与预测 |
| GET | `/calendar?start=&end=` | `[{date, holiday: string|null, isOffDay, weather: Weather|null}]` |

metric：`ENTRY`（入园人次）`EXIT`（出园人次）`IN_PARK`（在园人数）`ZONE_COUNT`（区域人数，需 zoneId）`QUEUE_WAIT`（实测排队分钟）。

```ts
interface FlowSummary { siteId; siteName; start; end; entries: number|null; exits: number|null; inParkCurrent: number|null;
  inParkPeak: number|null; inParkPeakAt; capacity: number; utilization: number|null; loadLevel: 'NORMAL'|'WARN'|'FULL'|'UNKNOWN';
  entriesYoy: number|null; entriesDod: number|null; notes: string[]; meta: DataMeta }
interface FlowTrend { siteId; zoneId: number|null; metric; granularity: 'HOUR'|'DAY'; aggregation: 'sum'|'max'|'last';
  points: { t: string; value: number }[]; gaps: { start; end }[]; meta: DataMeta }
interface ZoneLoad { zoneId; zoneName; zoneType; count: number|null; capacity: number|null; ratio: number|null;
  level: 'LOW'|'NORMAL'|'WARN'|'FULL'|'UNKNOWN'; at; countSource; meta: DataMeta }
interface ImportResult { batchId: string; total: number; inserted: number; duplicates: number; errors: { line: number; message: string }[] }
interface QueueStatus { pointId; name; pointType: 'RIDE'|'TICKET'|'GATE'|'SHOW'|'SERVICE'; siteId; zoneId; lon; lat;
  queueLength: number|null; serviceRatePerMin: number; estimatedWaitMin: number|null; measuredWaitMin: number|null;
  method: string; at; predicted: { t; waitMin: number }[]; meta: DataMeta }
interface Weather { tempMax; tempMin; tempMean; precipitation; snowfall; windMax; weatherCode; isForecast: boolean; source: string }
interface DailyForecast { siteId; siteName; metric: 'ENTRY'; model: { name; version; trainedAt; features: string[] };
  points: { date; predicted: number; lower: number; upper: number; actual: number|null; holiday: string|null; isOffDay: boolean;
            weather: Weather|null; drivers: string[] }[];
  backtest: Backtest|null; meta: DataMeta }
interface Backtest { modelName; modelVersion; testStart; testEnd; sampleDays; mape; mae; baselineName; baselineMape; baselineMae }
interface HourlyForecast { siteId; date; points: { t; predictedInPark: number; predictedEntries: number }[];
  peak: { t; value: number }; zonePeaks: { zoneId; zoneName; capacity; peak: number; peakAt; ratio; level; staffSuggested: number }[];
  recommendations: { level: 'INFO'|'WARN'|'CRITICAL'; title; detail; zoneId: number|null }[]; meta: DataMeta }
```

flows CSV：表头 `source_record_id,biz_time,zone_code,metric,value`；`zone_code` 可空；编码 UTF-8；重复 `source_record_id` 计入 duplicates 不重复计数；迟到数据触发相关时间桶重算。

## 4. 运营总览、业态、团队、画像、接入

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/overview` | `Overview`（全部授权景区，业务时钟“今天”） |
| GET | `/business/summary?siteId=&start=&end=` | 日期区间 `BusinessSummary` |
| GET | `/business/units?siteId=&category=` | 业态单元列表 |
| GET | `/business/linkage?siteId=&days=30` | 客流与业态联动分析 |
| GET | `/groups?siteId=&date=&agencyId=&status=&page=&size=` | 团队分页 |
| GET | `/groups/stats?siteId=&start=&end=` | 团队统计 |
| GET | `/agencies` | 旅行社列表 |
| GET | `/profiles?siteId=&month=YYYY-MM` | 游客画像 |
| GET | `/integrations` | 数据源接入状态 |
| GET | `/jobs?type=&status=&limit=`、POST `/jobs/{id}/retry` | 后台任务 |

```ts
interface Overview { now; sites: OverviewSite[]; totals: { entries: number|null; inPark: number|null; capacity: number };
  alerts: Alert[]; meta: DataMeta }
interface OverviewSite { siteId; name; shortName; status; capacity; center: GeoJSON.Point; today: FlowSummary;
  zones: { warn: number; full: number; total: number }; devices: { online: number; total: number; offline: number };
  reviews: { today: number; negative: number; avgRating: number|null }; inspections: { open: number; overdue: number; openIssues: number };
  queues: { name: string; waitMin: number|null }[]; groupsToday: { groups: number; visitors: number } }
interface Alert { level: 'INFO'|'WARN'|'CRITICAL'; type: 'ZONE_LOAD'|'DEVICE_OFFLINE'|'DATA_STALE'|'QUEUE'|'REVIEW'|'INSPECTION'|'FORECAST';
  siteId; title; detail; at; ref: string|null }
interface BusinessSummary { siteId; start; end; currency: 'CNY'; amountBasis: string;
  byCategory: { category: 'HOTEL'|'CATERING'|'RETAIL'|'TICKET'|'ENTERTAINMENT'; revenue: number|null; orders: number|null; guests: number|null; units: number; avgOccupancy: number|null }[];
  daily: { date; category; revenue: number|null }[]; topUnits: { unitId; name; category; revenue: number|null }[]; meta: DataMeta }
interface BusinessLinkage { siteId; points: { date; entries: number|null; hotelOccupancy: number|null; cateringRevenue: number|null; retailRevenue: number|null }[];
  correlations: { pair: string; r: number|null; n: number }[]; insights: string[]; meta: DataMeta }
interface TourGroup { id; groupCode; agencyId; agencyName; siteId; siteName; visitDate; plannedSize; enteredCount; guideName;
  guidePhoneMasked; guideCertNo; originRegion; status: 'BOOKED'|'ENTERED'|'LEFT'|'CANCELLED'; enteredAt; dataMode }
interface GroupStats { totalGroups; totalVisitors; byAgency: { agencyId; name; groups; visitors }[];
  byOrigin: { region; groups; visitors }[]; daily: { date; groups; visitors }[]; meta: DataMeta }
interface VisitorProfile { siteId; month; sampleSize; coverage: number;
  dimensions: Record<'ORIGIN'|'AGE'|'GENDER'|'CHANNEL'|'STAY', { bucket: string; visitors: number; share: number }[]>;
  notes: string[]; meta: DataMeta }
interface IntegrationSource { id; code; name; sourceType; dataMode; siteId; provider; licenseNote; refreshSeconds;
  watermark; lastSuccessAt; lastErrorAt; lastError; health: 'OK'|'FAILED'|'STALE'|'UNKNOWN'; enabled }
interface Job { id; jobType; idempotencyKey; refId; payload; status; attempts; maxAttempts; leaseOwner; leaseUntil;
  heartbeatAt; cancelRequested; runAfter; lastError; createdBy; createdAt; updatedAt; finishedAt }
```

游客画像未知/未识别分桶（`未知`）必须保留并展示；`coverage` 为样本覆盖率；不得从网评样本推断全量游客。

## 5. 巡检与文件

状态机（后端维护允许迁移表，客户端只提交 action）：

```
DRAFT --ASSIGN--> ASSIGNED --START--> IN_PROGRESS --SUBMIT--> SUBMITTED
SUBMITTED --CONFIRM--> COMPLETED            (无未关闭异常时)
SUBMITTED --REQUIRE_RECTIFY--> RECTIFYING   (存在异常时)
RECTIFYING --RECTIFY_DONE--> PENDING_REVIEW --APPROVE--> COMPLETED
PENDING_REVIEW --REJECT--> RECTIFYING
DRAFT/ASSIGNED --CANCEL--> CANCELLED
```

| action | 允许角色/条件 |
|---|---|
| ASSIGN | INSPECTION_MANAGE；需 assigneeId（须为该景区巡检员） |
| START / SUBMIT | INSPECTION_EXECUTE 且为任务执行人；SUBMIT 要求必填检查项均有结果 |
| CONFIRM / REQUIRE_RECTIFY / APPROVE / REJECT | INSPECTION_REVIEW（任务指定复核人或景区管理员） |
| RECTIFY_DONE | 执行人或 INSPECTION_MANAGE；需 rectifyNote |
| CANCEL | INSPECTION_MANAGE |

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/inspection-templates` | `[{id, name, category, items: [{code, name, required, hint}]}]` |
| GET | `/inspection-tasks?siteId=&status=&mine=&keyword=&page=&size=` | 分页 `TaskView`；`mine=true` 仅本人执行的任务 |
| POST | `/inspection-tasks` | `{siteId, zoneId?, templateId, title, description?, priority, assigneeId?, reviewerId?, dueAt?}` → 201 `TaskDetail`（DRAFT） |
| GET | `/inspection-tasks/{id}` | `TaskDetail` |
| POST | `/inspection-tasks/{id}/transitions` | `{action, version, note?, assigneeId?, reviewerId?, rectifyNote?, rectifyFileIds?}` → `TaskDetail` |
| POST | `/inspection-tasks/{id}/results` | `{version, items: [{itemCode, result: 'OK'|'ABNORMAL'|'NA', remark?, fileIds: []}], issues: [{itemCode?, description, severity: 'LOW'|'MEDIUM'|'HIGH', fileIds: []}]}` → `TaskDetail`（仅 IN_PROGRESS，执行人；可多次保存） |
| GET | `/inspection/candidates?siteId=&role=INSPECTOR|REVIEWER` | 可指派人员 `[{id, displayName}]` |
| GET | `/inspection/stats?siteId=&start=&end=` | `InspectionSummary`（见 `InspectionQueryPort`） |
| POST | `/files` | multipart：`file`、`siteId`、`bizType`、`bizId?` → `FileRef`；仅 jpeg/png/webp，≤10MB，校验文件头 |
| GET | `/files/{id}` | 文件内容（校验景区范围） |

```ts
interface TaskView { id; taskNo; siteId; siteName; zoneId; zoneName; templateId; templateName; title; description;
  priority: 'LOW'|'NORMAL'|'HIGH'|'URGENT'; assigneeId; assigneeName; reviewerId; reviewerName; dueAt; status; origin: 'MANUAL'|'AGENT_DRAFT'|'SCHEDULE';
  overdue: boolean; issueCount: number; openIssueCount: number; version: number; createdAt; updatedAt }
interface TaskDetail extends TaskView { items: { code; name; required; hint }[];
  results: { itemCode; itemName; result; remark; files: FileRef[] }[];
  issues: { id; itemCode; description; severity; status: 'OPEN'|'RECTIFIED'|'CLOSED'; rectifierId; rectifierName; rectifyNote;
            rectifyFiles: FileRef[]; reviewNote; files: FileRef[]; createdAt; updatedAt }[];
  logs: { action; fromStatus; toStatus; actorName; note; createdAt }[];
  allowedActions: string[]; basisRefs: string[] | null }
interface FileRef { id: number; name: string; contentType: string; size: number; url: string }
```

## 6. 网评

主题字典（受控）：`QUEUE` 排队等候、`TICKET` 票务价格、`HYGIENE` 卫生环境、`SERVICE` 服务态度、`TRAFFIC` 交通停车、`CATERING` 餐饮、`FACILITY` 设施设备、`SAFETY` 安全秩序、`SCENERY` 景观体验、`COLD` 寒冷保暖、`OTHER` 其他。
情绪：`POSITIVE` `NEUTRAL` `NEGATIVE` `MIXED` `UNKNOWN`。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/reviews/topics` | `[{code, name}]` |
| GET | `/reviews?siteId=&sentiment=&topic=&keyword=&status=&complaint=&start=&end=&page=&size=` | 分页 `ReviewView` |
| GET | `/reviews/{id}` | `ReviewDetail` |
| PATCH | `/reviews/{id}/classification` | `{sentiment, topics, summary?, note?, expectedVersion}` → `ReviewDetail`；生成 HUMAN 新版本，后续批量重跑不会覆盖人工结果 |
| GET | `/reviews/summary?siteId=&start=&end=` | `ReviewSummary` |
| POST | `/imports/reviews` | multipart：`file`(CSV) → `ImportResult` |
| POST | `/reviews/analysis-jobs` | `{siteId?, reviewIds?, limit?}` → 202 `{runId}`（Agent REVIEW_ANALYSIS 任务） |

```ts
interface Analysis { version; origin: 'MODEL'|'HUMAN'; sentiment; topics: string[]; summary; needsHumanReview: boolean;
  evidenceSpans: { start: number; end: number; text: string; topic: string }[]; model; promptVersion; createdByName; note; createdAt }
interface ReviewView { id; siteId; siteName; platform; authorMasked; rating: number|null; contentMasked; publishedAt;
  isComplaint; dataMode; analysisStatus: 'PENDING'|'ANALYZED'|'HUMAN'|'FAILED'; analysis: Analysis|null }
interface ReviewDetail extends ReviewView { history: Analysis[]; sourceRecordId; importedAt; importBatch }
interface ReviewSummary { siteId: number|null; start; end; total; avgRating: number|null; complaintCount;
  bySentiment: Record<string, number>; byTopic: { topic; name; count; negative }[]; byPlatform: { platform; count }[];
  daily: { date; total; negative }[]; hotIssues: { topic; name; count; samples: { reviewId; text }[] }[]; meta: DataMeta }
```

reviews CSV：表头 `source_record_id,site_code,platform,published_at,rating,content,author,is_complaint`；`author`、`is_complaint`、`rating` 可空；逐行错误返回行号。导入时自动脱敏手机号、证件号。

## 7. AI 运营助手（Agent）、报告、营销

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/agent/runs` | 见下；→ 202 `{runId, status}` |
| GET | `/agent/runs?scenario=&page=&size=` | 本人任务分页 |
| GET | `/agent/runs/{id}` | `AgentRun` |
| GET | `/agent/runs/{id}/events` | SSE；事件 `status` `step` `done`；断开后可用 GET 轮询降级 |
| POST | `/agent/runs/{id}/cancel` | 取消 |
| GET | `/agent/usage` | `{date, runs, tokens, limits: {runs, tokens}}` |
| GET | `/reports?siteId=&page=&size=` | 报告列表 |
| GET | `/reports/{id}` | `Report` |
| POST | `/reports` | `{siteId, reportDate}` → 202 `{runId}` 生成新版本 |
| GET | `/marketing-plans`、`/marketing-plans/{id}` | 营销方案草稿 |
| PATCH | `/marketing-plans/{id}/status` | `{status: 'APPROVED'|'ARCHIVED'}`（仅内部状态，不对外发布） |

POST `/agent/runs` body：

```ts
{ scenario: 'QA', question: string, siteIds?: number[], idempotencyKey?: string }
{ scenario: 'DAILY_REPORT', siteId: number, reportDate: string }
{ scenario: 'MARKETING', siteIds?: number[], goal: string, segment?: string }
{ scenario: 'REVIEW_ANALYSIS', siteId?: number, reviewIds?: number[], limit?: number }
```

```ts
interface AgentRun { id; scenario; status: 'QUEUED'|'RUNNING'|'SUCCEEDED'|'FAILED'|'CANCELLED'; progress: string|null;
  input; result: QaResult | ReportRef | MarketingRef | ReviewBatchResult | null; errorCode; errorMessage;
  provider: 'fake'|'deepseek'; model; promptVersion; modelCalls; toolCalls; tokensIn; tokensOut; createdAt; startedAt; finishedAt;
  steps: { seq; toolName; status: 'OK'|'ERROR'|'DENIED'|'INVALID'; arguments; resultSummary; sourceIds: string[]; durationMs; createdAt }[] }
interface QaResult { answer: string; facts: { metric; value: number|string|null; unit; period; sourceIds: string[] }[];
  evidence: { sourceId; label; excerpt }[]; limitations: string[]; dataAsOf; dataMode }
interface ReportRef { reportId; version }
interface MarketingRef { planId }
interface ReviewBatchResult { analyzed; skippedHuman; failed; needsHumanReview }
interface Report { id; siteId; siteName; reportDate; version; title; dataAsOf; dataMode; provider; model; promptVersion; createdAt; createdByName;
  snapshot: object; content: { summary: string; sections: { title; kind: 'FACT'|'ANALYSIS'|'ADVICE'; paragraphs: string[];
    facts: { metric; value; unit; sourceIds }[] }[]; risks: string[]; suggestions: { title; detail; basis: string[] }[]; coverage: string } }
interface MarketingPlan { id; title; siteIds: number[]; targetSegment; status; createdAt; createdByName; agentRunId;
  content: { summary; segments: { name; evidence; share: number|null }[]; packages: { name; items: string[]; priceHint: string; targetDays: string; rationale; basis: string[] }[];
             channels: string[]; kpis: string[]; risks: string[]; disclaimer: string } }
```

Agent 工具（模型只能调用以下工具，景区范围由服务端注入）：`get_flow_summary` `get_flow_trend` `get_zone_loads` `get_queue_status` `get_forecast` `get_review_summary` `get_review_evidence` `get_inspection_summary` `get_device_status` `get_business_summary` `get_group_stats` `get_visitor_profile` `create_inspection_draft`（需 AI_DRAFT_INSPECTION，仅创建草稿）。

所有模型生成文本前端按纯文本/受限 Markdown 安全渲染，禁止 `v-html` 直接渲染未净化内容。
