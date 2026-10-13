-- snow_world baseline schema. Geometry is WGS84 (EPSG:4326). Time ranges are half-open [start, end).
CREATE EXTENSION IF NOT EXISTS postgis;

-- ============ 身份与授权 ============
CREATE TABLE app_user (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(200) NOT NULL,
    display_name  VARCHAR(64)  NOT NULL,
    phone_masked  VARCHAR(32),
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    all_sites     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE role (
    code        VARCHAR(32) PRIMARY KEY,
    name        VARCHAR(64) NOT NULL,
    description VARCHAR(200)
);

CREATE TABLE user_role (
    user_id   BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role_code VARCHAR(32) NOT NULL REFERENCES role (code),
    PRIMARY KEY (user_id, role_code)
);

-- ============ 景区、区域、设备 ============
CREATE TABLE scenic_site (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code         VARCHAR(32)  NOT NULL UNIQUE,
    name         VARCHAR(64)  NOT NULL,
    short_name   VARCHAR(32)  NOT NULL,
    timezone     VARCHAR(40)  NOT NULL DEFAULT 'Asia/Shanghai',
    capacity     INTEGER      NOT NULL CHECK (capacity > 0),
    warn_ratio   NUMERIC(4, 3) NOT NULL DEFAULT 0.8 CHECK (warn_ratio > 0 AND warn_ratio <= 1),
    open_time    TIME,
    close_time   TIME,
    season_note  VARCHAR(200),
    description  TEXT,
    center       geometry(Point, 4326) NOT NULL,
    boundary     geometry(MultiPolygon, 4326),
    boundary_src VARCHAR(200),
    status       VARCHAR(16)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED', 'SEASONAL')),
    sort_order   INTEGER      NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_scenic_site_boundary ON scenic_site USING gist (boundary);

CREATE TABLE user_scenic_scope (
    user_id BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    site_id BIGINT NOT NULL REFERENCES scenic_site (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, site_id)
);

CREATE TABLE scenic_zone (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    site_id     BIGINT      NOT NULL REFERENCES scenic_site (id),
    code        VARCHAR(32) NOT NULL,
    name        VARCHAR(64) NOT NULL,
    zone_type   VARCHAR(24) NOT NULL DEFAULT 'AREA'
        CHECK (zone_type IN ('AREA', 'ENTRANCE', 'ATTRACTION', 'SERVICE', 'PARKING', 'COMMERCIAL')),
    boundary    geometry(Polygon, 4326) NOT NULL,
    capacity    INTEGER     CHECK (capacity IS NULL OR capacity > 0),
    warn_ratio  NUMERIC(4, 3) NOT NULL DEFAULT 0.8,
    -- 区域人数来源说明: 围栏本身不产生人数, 需绑定计数设备
    count_source VARCHAR(200),
    remark      VARCHAR(200),
    version     INTEGER     NOT NULL DEFAULT 0,
    active      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_by  BIGINT REFERENCES app_user (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (site_id, code)
);
CREATE INDEX idx_scenic_zone_boundary ON scenic_zone USING gist (boundary);

CREATE TABLE device (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    site_id          BIGINT      NOT NULL REFERENCES scenic_site (id),
    zone_id          BIGINT REFERENCES scenic_zone (id),
    code             VARCHAR(48) NOT NULL UNIQUE,
    name             VARCHAR(64) NOT NULL,
    device_type      VARCHAR(16) NOT NULL CHECK (device_type IN ('CAMERA', 'GATE', 'COUNTER', 'SENSOR', 'SCREEN')),
    key_point        BOOLEAN     NOT NULL DEFAULT FALSE,
    location         geometry(Point, 4326) NOT NULL,
    vendor           VARCHAR(64),
    protocol         VARCHAR(32),
    source_device_id VARCHAR(128),
    stream_ref       VARCHAR(200),
    status           VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' CHECK (status IN ('ONLINE', 'OFFLINE', 'FAULT', 'UNKNOWN')),
    status_at        TIMESTAMPTZ,
    data_mode        VARCHAR(8)  NOT NULL DEFAULT 'DEMO' CHECK (data_mode IN ('REAL', 'DEMO')),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_device_site ON device (site_id, device_type);
CREATE INDEX idx_device_location ON device USING gist (location);

-- 开放地图兴趣点(OSM), 仅作为空间参考
CREATE TABLE poi (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    site_id    BIGINT REFERENCES scenic_site (id),
    osm_ref    VARCHAR(40) UNIQUE,
    name       VARCHAR(200),
    category   VARCHAR(32) NOT NULL,
    sub_type   VARCHAR(40),
    geom       geometry(Point, 4326) NOT NULL
);
CREATE INDEX idx_poi_geom ON poi USING gist (geom);

-- ============ 数据接入 ============
CREATE TABLE integration_source (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code            VARCHAR(48)  NOT NULL UNIQUE,
    name            VARCHAR(100) NOT NULL,
    source_type     VARCHAR(16)  NOT NULL
        CHECK (source_type IN ('FLOW', 'REVIEW', 'QUEUE', 'BUSINESS', 'GROUP', 'PROFILE', 'VIDEO', 'WEATHER', 'HOLIDAY', 'MAP')),
    data_mode       VARCHAR(8)   NOT NULL CHECK (data_mode IN ('REAL', 'DEMO')),
    site_id         BIGINT REFERENCES scenic_site (id),
    provider        VARCHAR(100),
    license_note    VARCHAR(200),
    config_ref      VARCHAR(100),
    refresh_seconds INTEGER,
    watermark       TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    last_error_at   TIMESTAMPTZ,
    last_error      VARCHAR(500),
    health          VARCHAR(16)  NOT NULL DEFAULT 'UNKNOWN' CHECK (health IN ('OK', 'FAILED', 'STALE', 'UNKNOWN')),
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ============ 客流 ============
-- metric: ENTRY/EXIT 时段人次; IN_PARK 在园存量; ZONE_COUNT 区域瞬时人数; QUEUE_LEN 排队人数; QUEUE_WAIT 实测排队分钟
CREATE TABLE flow_event (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id        BIGINT        NOT NULL REFERENCES integration_source (id),
    source_record_id VARCHAR(128)  NOT NULL,
    site_id          BIGINT        NOT NULL REFERENCES scenic_site (id),
    zone_id          BIGINT REFERENCES scenic_zone (id),
    device_id        BIGINT REFERENCES device (id),
    metric           VARCHAR(16)   NOT NULL CHECK (metric IN ('ENTRY', 'EXIT', 'IN_PARK', 'ZONE_COUNT', 'QUEUE_LEN', 'QUEUE_WAIT')),
    value            NUMERIC(14, 2) NOT NULL,
    biz_time         TIMESTAMPTZ   NOT NULL,
    received_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    quality          VARCHAR(12)   NOT NULL DEFAULT 'GOOD' CHECK (quality IN ('GOOD', 'SUSPECT', 'CORRECTED')),
    data_mode        VARCHAR(8)    NOT NULL CHECK (data_mode IN ('REAL', 'DEMO')),
    UNIQUE (source_id, source_record_id)
);
CREATE INDEX idx_flow_event_lookup ON flow_event (site_id, metric, biz_time);
CREATE INDEX idx_flow_event_zone ON flow_event (zone_id, metric, biz_time) WHERE zone_id IS NOT NULL;

-- zone_id = 0 表示景区整体; 汇总只由后端统一计算
CREATE TABLE flow_aggregate (
    site_id      BIGINT        NOT NULL REFERENCES scenic_site (id),
    zone_id      BIGINT        NOT NULL DEFAULT 0,
    metric       VARCHAR(16)   NOT NULL,
    granularity  VARCHAR(8)    NOT NULL CHECK (granularity IN ('HOUR', 'DAY')),
    bucket_start TIMESTAMPTZ   NOT NULL,
    sum_value    NUMERIC(16, 2) NOT NULL,
    max_value    NUMERIC(14, 2) NOT NULL,
    avg_value    NUMERIC(14, 2) NOT NULL,
    last_value   NUMERIC(14, 2) NOT NULL,
    sample_count INTEGER       NOT NULL,
    data_mode    VARCHAR(8)    NOT NULL,
    rule_version VARCHAR(16)   NOT NULL DEFAULT 'v1',
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (site_id, zone_id, metric, granularity, bucket_start)
);

-- 排队点位(游乐项目、售票处等), 预估等待时间按服务能力推算
CREATE TABLE queue_point (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    site_id            BIGINT      NOT NULL REFERENCES scenic_site (id),
    zone_id            BIGINT REFERENCES scenic_zone (id),
    device_id          BIGINT REFERENCES device (id),
    name               VARCHAR(64) NOT NULL,
    point_type         VARCHAR(16) NOT NULL DEFAULT 'RIDE' CHECK (point_type IN ('RIDE', 'TICKET', 'GATE', 'SHOW', 'SERVICE')),
    location           geometry(Point, 4326) NOT NULL,
    service_rate_per_min NUMERIC(8, 2) NOT NULL CHECK (service_rate_per_min > 0),
    active             BOOLEAN     NOT NULL DEFAULT TRUE
);

-- ============ 外部特征: 天气 / 节假日 ============
CREATE TABLE weather_daily (
    location_code VARCHAR(32)  NOT NULL,
    biz_date      DATE         NOT NULL,
    temp_max      NUMERIC(5, 1),
    temp_min      NUMERIC(5, 1),
    temp_mean     NUMERIC(5, 1),
    precipitation NUMERIC(6, 1),
    snowfall      NUMERIC(6, 1),
    wind_max      NUMERIC(6, 1),
    weather_code  INTEGER,
    is_forecast   BOOLEAN      NOT NULL DEFAULT FALSE,
    source        VARCHAR(64)  NOT NULL,
    fetched_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (location_code, biz_date)
);

CREATE TABLE holiday_calendar (
    biz_date  DATE         PRIMARY KEY,
    name      VARCHAR(32)  NOT NULL,
    is_off_day BOOLEAN     NOT NULL,
    source    VARCHAR(200) NOT NULL
);

-- ============ 预测 ============
CREATE TABLE flow_forecast (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    site_id       BIGINT       NOT NULL REFERENCES scenic_site (id),
    zone_id       BIGINT       NOT NULL DEFAULT 0,
    metric        VARCHAR(16)  NOT NULL,
    granularity   VARCHAR(8)   NOT NULL,
    target_start  TIMESTAMPTZ  NOT NULL,
    predicted     NUMERIC(14, 2) NOT NULL,
    lower_bound   NUMERIC(14, 2),
    upper_bound   NUMERIC(14, 2),
    model_name    VARCHAR(48)  NOT NULL,
    model_version VARCHAR(16)  NOT NULL,
    features      JSONB,
    generated_at  TIMESTAMPTZ  NOT NULL,
    batch_id      VARCHAR(40)  NOT NULL
);
CREATE INDEX idx_flow_forecast_lookup ON flow_forecast (site_id, zone_id, metric, granularity, target_start, generated_at DESC);

CREATE TABLE forecast_backtest (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    site_id        BIGINT       NOT NULL REFERENCES scenic_site (id),
    model_name     VARCHAR(48)  NOT NULL,
    model_version  VARCHAR(16)  NOT NULL,
    train_end      DATE         NOT NULL,
    test_start     DATE         NOT NULL,
    test_end       DATE         NOT NULL,
    mape           NUMERIC(8, 4),
    mae            NUMERIC(14, 2),
    baseline_name  VARCHAR(48)  NOT NULL,
    baseline_mape  NUMERIC(8, 4),
    baseline_mae   NUMERIC(14, 2),
    sample_days    INTEGER      NOT NULL,
    detail         JSONB,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ============ 网评 ============
CREATE TABLE review (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id        BIGINT        NOT NULL REFERENCES integration_source (id),
    source_record_id VARCHAR(128)  NOT NULL,
    site_id          BIGINT        NOT NULL REFERENCES scenic_site (id),
    platform         VARCHAR(32)   NOT NULL,
    author_masked    VARCHAR(64),
    rating           NUMERIC(3, 1),
    content          TEXT          NOT NULL,
    content_masked   TEXT          NOT NULL,
    published_at     TIMESTAMPTZ   NOT NULL,
    imported_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    import_batch     VARCHAR(40),
    is_complaint     BOOLEAN       NOT NULL DEFAULT FALSE,
    data_mode        VARCHAR(8)    NOT NULL CHECK (data_mode IN ('REAL', 'DEMO')),
    analysis_status  VARCHAR(16)   NOT NULL DEFAULT 'PENDING' CHECK (analysis_status IN ('PENDING', 'ANALYZED', 'HUMAN', 'FAILED')),
    UNIQUE (source_id, source_record_id)
);
CREATE INDEX idx_review_site_time ON review (site_id, published_at DESC);

CREATE TABLE review_analysis (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    review_id          BIGINT       NOT NULL REFERENCES review (id) ON DELETE CASCADE,
    version            INTEGER      NOT NULL,
    origin             VARCHAR(8)   NOT NULL CHECK (origin IN ('MODEL', 'HUMAN')),
    sentiment          VARCHAR(10)  NOT NULL CHECK (sentiment IN ('POSITIVE', 'NEUTRAL', 'NEGATIVE', 'MIXED', 'UNKNOWN')),
    topics             VARCHAR(24)[] NOT NULL DEFAULT '{}',
    summary            VARCHAR(300),
    evidence_spans     JSONB        NOT NULL DEFAULT '[]',
    needs_human_review BOOLEAN      NOT NULL DEFAULT FALSE,
    model              VARCHAR(64),
    prompt_version     VARCHAR(16),
    agent_run_id       BIGINT,
    is_current         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by         BIGINT REFERENCES app_user (id),
    note               VARCHAR(300),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (review_id, version)
);
CREATE UNIQUE INDEX uq_review_analysis_current ON review_analysis (review_id) WHERE is_current;
CREATE INDEX idx_review_analysis_topics ON review_analysis USING gin (topics) WHERE is_current;

-- ============ 巡检 ============
CREATE TABLE inspection_template (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(64)  NOT NULL,
    category   VARCHAR(32)  NOT NULL,
    items      JSONB        NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE inspection_task (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_no          VARCHAR(32)  NOT NULL UNIQUE,
    site_id          BIGINT       NOT NULL REFERENCES scenic_site (id),
    zone_id          BIGINT REFERENCES scenic_zone (id),
    template_id      BIGINT       NOT NULL REFERENCES inspection_template (id),
    title            VARCHAR(100) NOT NULL,
    description      VARCHAR(500),
    priority         VARCHAR(8)   NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    assignee_id      BIGINT REFERENCES app_user (id),
    reviewer_id      BIGINT REFERENCES app_user (id),
    due_at           TIMESTAMPTZ,
    status           VARCHAR(16)  NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'ASSIGNED', 'IN_PROGRESS', 'SUBMITTED', 'RECTIFYING', 'PENDING_REVIEW', 'COMPLETED', 'CANCELLED')),
    origin           VARCHAR(12)  NOT NULL DEFAULT 'MANUAL' CHECK (origin IN ('MANUAL', 'AGENT_DRAFT', 'SCHEDULE')),
    agent_run_id     BIGINT,
    basis_refs       JSONB,
    idempotency_key  VARCHAR(80) UNIQUE,
    version          INTEGER      NOT NULL DEFAULT 0,
    created_by       BIGINT REFERENCES app_user (id),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    submitted_at     TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ
);
CREATE INDEX idx_inspection_task_site ON inspection_task (site_id, status, due_at);
CREATE INDEX idx_inspection_task_assignee ON inspection_task (assignee_id, status);

CREATE TABLE inspection_item_result (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id    BIGINT       NOT NULL REFERENCES inspection_task (id) ON DELETE CASCADE,
    item_code  VARCHAR(32)  NOT NULL,
    item_name  VARCHAR(100) NOT NULL,
    result     VARCHAR(10)  NOT NULL CHECK (result IN ('OK', 'ABNORMAL', 'NA')),
    remark     VARCHAR(500),
    file_ids   BIGINT[]     NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (task_id, item_code)
);

CREATE TABLE inspection_issue (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id       BIGINT       NOT NULL REFERENCES inspection_task (id) ON DELETE CASCADE,
    item_code     VARCHAR(32),
    description   VARCHAR(500) NOT NULL,
    severity      VARCHAR(8)   NOT NULL DEFAULT 'MEDIUM' CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH')),
    status        VARCHAR(12)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RECTIFIED', 'CLOSED')),
    rectifier_id  BIGINT REFERENCES app_user (id),
    rectify_note  VARCHAR(500),
    rectify_files BIGINT[]     NOT NULL DEFAULT '{}',
    review_note   VARCHAR(500),
    file_ids      BIGINT[]     NOT NULL DEFAULT '{}',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE inspection_log (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id     BIGINT      NOT NULL REFERENCES inspection_task (id) ON DELETE CASCADE,
    action      VARCHAR(24) NOT NULL,
    from_status VARCHAR(16),
    to_status   VARCHAR(16),
    actor_id    BIGINT REFERENCES app_user (id),
    note        VARCHAR(500),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_inspection_log_task ON inspection_log (task_id, created_at);

-- ============ 文件 ============
CREATE TABLE file_asset (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    object_key   VARCHAR(200) NOT NULL UNIQUE,
    original_name VARCHAR(200),
    content_type VARCHAR(100) NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    sha256       CHAR(64)     NOT NULL,
    site_id      BIGINT       NOT NULL REFERENCES scenic_site (id),
    biz_type     VARCHAR(32)  NOT NULL,
    biz_id       BIGINT,
    uploader_id  BIGINT       NOT NULL REFERENCES app_user (id),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ============ 多业态 ============
CREATE TABLE business_unit (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    site_id    BIGINT       NOT NULL REFERENCES scenic_site (id),
    poi_id     BIGINT REFERENCES poi (id),
    code       VARCHAR(32)  NOT NULL UNIQUE,
    name       VARCHAR(100) NOT NULL,
    category   VARCHAR(16)  NOT NULL CHECK (category IN ('HOTEL', 'CATERING', 'RETAIL', 'TICKET', 'ENTERTAINMENT')),
    location   geometry(Point, 4326),
    capacity   INTEGER,
    data_mode  VARCHAR(8)   NOT NULL CHECK (data_mode IN ('REAL', 'DEMO'))
);

-- 金额使用精确小数并单独保存币种; amount_basis 说明口径(支付/核销/入账)
CREATE TABLE business_daily (
    unit_id      BIGINT        NOT NULL REFERENCES business_unit (id),
    biz_date     DATE          NOT NULL,
    revenue      NUMERIC(14, 2),
    currency     CHAR(3)       NOT NULL DEFAULT 'CNY',
    amount_basis VARCHAR(16)   NOT NULL DEFAULT 'PAID',
    orders       INTEGER,
    guests       INTEGER,
    occupancy    NUMERIC(5, 4),
    source_id    BIGINT        NOT NULL REFERENCES integration_source (id),
    data_mode    VARCHAR(8)    NOT NULL,
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (unit_id, biz_date)
);

-- ============ 旅行社与团队 ============
CREATE TABLE travel_agency (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(32)  NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    license_no  VARCHAR(64),
    region      VARCHAR(32),
    contact_masked VARCHAR(32),
    data_mode   VARCHAR(8)   NOT NULL
);

CREATE TABLE tour_group (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_code     VARCHAR(40)  NOT NULL UNIQUE,
    agency_id      BIGINT       NOT NULL REFERENCES travel_agency (id),
    site_id        BIGINT       NOT NULL REFERENCES scenic_site (id),
    visit_date     DATE         NOT NULL,
    planned_size   INTEGER      NOT NULL CHECK (planned_size > 0),
    entered_count  INTEGER      NOT NULL DEFAULT 0 CHECK (entered_count >= 0),
    guide_name     VARCHAR(32),
    guide_phone_masked VARCHAR(32),
    guide_cert_no  VARCHAR(32),
    origin_region  VARCHAR(32),
    status         VARCHAR(12)  NOT NULL DEFAULT 'BOOKED' CHECK (status IN ('BOOKED', 'ENTERED', 'LEFT', 'CANCELLED')),
    entered_at     TIMESTAMPTZ,
    source_id      BIGINT       NOT NULL REFERENCES integration_source (id),
    data_mode      VARCHAR(8)   NOT NULL
);
CREATE INDEX idx_tour_group_site_date ON tour_group (site_id, visit_date);

-- ============ 游客画像(聚合统计, 保留未知分类与覆盖率) ============
CREATE TABLE visitor_profile_stat (
    site_id     BIGINT       NOT NULL REFERENCES scenic_site (id),
    stat_month  DATE         NOT NULL,
    dimension   VARCHAR(16)  NOT NULL CHECK (dimension IN ('ORIGIN', 'AGE', 'GENDER', 'CHANNEL', 'STAY')),
    bucket      VARCHAR(32)  NOT NULL,
    visitors    INTEGER      NOT NULL,
    sample_size INTEGER      NOT NULL,
    coverage    NUMERIC(5, 4) NOT NULL,
    source_id   BIGINT       NOT NULL REFERENCES integration_source (id),
    data_mode   VARCHAR(8)   NOT NULL,
    PRIMARY KEY (site_id, stat_month, dimension, bucket)
);

-- ============ 营销方案草稿 ============
CREATE TABLE marketing_plan (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title         VARCHAR(100) NOT NULL,
    site_ids      BIGINT[]     NOT NULL,
    target_segment VARCHAR(200),
    content       JSONB        NOT NULL,
    status        VARCHAR(12)  NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'APPROVED', 'ARCHIVED')),
    agent_run_id  BIGINT,
    created_by    BIGINT       NOT NULL REFERENCES app_user (id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ============ Agent ============
CREATE TABLE agent_run (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id          BIGINT       NOT NULL REFERENCES app_user (id),
    scenario         VARCHAR(20)  NOT NULL CHECK (scenario IN ('QA', 'REVIEW_ANALYSIS', 'DAILY_REPORT', 'MARKETING')),
    site_scope       BIGINT[]     NOT NULL,
    input            JSONB        NOT NULL,
    status           VARCHAR(12)  NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    progress         VARCHAR(200),
    result           JSONB,
    error_code       VARCHAR(40),
    error_message    VARCHAR(500),
    provider         VARCHAR(16),
    model            VARCHAR(64),
    prompt_version   VARCHAR(16),
    model_calls      INTEGER      NOT NULL DEFAULT 0,
    tool_calls       INTEGER      NOT NULL DEFAULT 0,
    tokens_in        INTEGER      NOT NULL DEFAULT 0,
    tokens_out       INTEGER      NOT NULL DEFAULT 0,
    cancel_requested BOOLEAN      NOT NULL DEFAULT FALSE,
    idempotency_key  VARCHAR(80)  UNIQUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ
);
CREATE INDEX idx_agent_run_user ON agent_run (user_id, created_at DESC);

CREATE TABLE agent_message (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id       BIGINT      NOT NULL REFERENCES agent_run (id) ON DELETE CASCADE,
    seq          INTEGER     NOT NULL,
    role         VARCHAR(12) NOT NULL,
    content      TEXT,
    tool_call_id VARCHAR(80),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (run_id, seq)
);

CREATE TABLE agent_tool_call (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id         BIGINT       NOT NULL REFERENCES agent_run (id) ON DELETE CASCADE,
    seq            INTEGER      NOT NULL,
    call_id        VARCHAR(80),
    tool_name      VARCHAR(48)  NOT NULL,
    arguments      JSONB,
    status         VARCHAR(12)  NOT NULL CHECK (status IN ('OK', 'ERROR', 'DENIED', 'INVALID')),
    result_summary VARCHAR(300),
    result         JSONB,
    source_ids     VARCHAR(80)[] NOT NULL DEFAULT '{}',
    duration_ms    INTEGER,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (run_id, seq)
);

CREATE TABLE ai_usage_daily (
    user_id   BIGINT  NOT NULL REFERENCES app_user (id),
    biz_date  DATE    NOT NULL,
    runs      INTEGER NOT NULL DEFAULT 0,
    tokens    INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, biz_date)
);

-- ============ 运营报告 ============
CREATE TABLE operation_report (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    site_id        BIGINT       NOT NULL REFERENCES scenic_site (id),
    report_date    DATE         NOT NULL,
    version        INTEGER      NOT NULL,
    title          VARCHAR(100) NOT NULL,
    snapshot       JSONB        NOT NULL,
    content        JSONB        NOT NULL,
    data_as_of     TIMESTAMPTZ  NOT NULL,
    data_mode      VARCHAR(8)   NOT NULL,
    provider       VARCHAR(16),
    model          VARCHAR(64),
    prompt_version VARCHAR(16),
    agent_run_id   BIGINT REFERENCES agent_run (id),
    created_by     BIGINT       NOT NULL REFERENCES app_user (id),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (site_id, report_date, version)
);

-- ============ 后台任务 ============
CREATE TABLE background_job (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_type         VARCHAR(32)  NOT NULL,
    idempotency_key  VARCHAR(100) UNIQUE,
    ref_id           BIGINT,
    payload          JSONB        NOT NULL DEFAULT '{}',
    status           VARCHAR(12)  NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    attempts         INTEGER      NOT NULL DEFAULT 0,
    max_attempts     INTEGER      NOT NULL DEFAULT 3,
    lease_owner      VARCHAR(64),
    lease_until      TIMESTAMPTZ,
    heartbeat_at     TIMESTAMPTZ,
    cancel_requested BOOLEAN      NOT NULL DEFAULT FALSE,
    run_after        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error       VARCHAR(1000),
    created_by       BIGINT REFERENCES app_user (id),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ
);
CREATE INDEX idx_background_job_poll ON background_job (status, run_after) WHERE status IN ('QUEUED', 'RUNNING');

-- ============ 审计 ============
CREATE TABLE audit_log (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id   BIGINT REFERENCES app_user (id),
    actor_name VARCHAR(64),
    action     VARCHAR(48)  NOT NULL,
    target_type VARCHAR(32),
    target_id  VARCHAR(64),
    site_id    BIGINT,
    outcome    VARCHAR(12)  NOT NULL,
    detail     VARCHAR(1000),
    trace_id   VARCHAR(40),
    client_ip  VARCHAR(64),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_log_time ON audit_log (created_at DESC);

INSERT INTO role (code, name, description) VALUES
    ('GROUP_ADMIN', '集团管理员', '管理全部景区及授权'),
    ('SITE_ADMIN', '景区管理员', '管理被授权景区的设备、数据和巡检'),
    ('OPERATOR', '运营人员', '查询授权数据、分析网评、生成报告'),
    ('INSPECTOR', '巡检人员', '执行本人巡检任务、提交记录和异常'),
    ('REVIEWER', '复核人员', '确认整改结果、退回处理'),
    ('VIEWER', '只读查看者', '查看授权大屏及报表');
