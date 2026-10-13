-- 仅在可随时重建的独立演示快照中运行；完整历史库保持保留。
DO $$ BEGIN IF current_database()<>'snow_world_showcase' THEN RAISE EXCEPTION 'Only showcase database is allowed'; END IF; END $$;
BEGIN;
DELETE FROM flow_event WHERE biz_time>='2026-02-18T00:00:00+08';
DELETE FROM flow_aggregate WHERE bucket_start>='2026-02-18T00:00:00+08';
DELETE FROM business_daily WHERE biz_date>'2026-02-18';
DELETE FROM tour_group WHERE visit_date>'2026-02-23';
UPDATE tour_group SET status='BOOKED',entered_count=0,entered_at=NULL WHERE visit_date>'2026-02-18' OR (visit_date='2026-02-18' AND entered_at>'2026-02-18T14:30:00+08');
DELETE FROM visitor_profile_stat WHERE stat_month>'2026-02-01';
DELETE FROM review WHERE published_at>'2026-02-18T14:30:00+08';
UPDATE review_analysis a SET created_at=least(a.created_at,r.published_at) FROM review r WHERE a.review_id=r.id;
DELETE FROM inspection_task WHERE created_at>'2026-02-18T14:30:00+08';
DELETE FROM inspection_log WHERE created_at>'2026-02-18T14:30:00+08';
DELETE FROM inspection_item_result WHERE created_at>'2026-02-18T14:30:00+08';
DELETE FROM inspection_issue WHERE created_at>'2026-02-18T14:30:00+08';
UPDATE inspection_task t SET status=l.to_status,version=l.n-1,updated_at=l.created_at,submitted_at=CASE WHEN t.submitted_at>'2026-02-18T14:30:00+08' THEN NULL ELSE t.submitted_at END,completed_at=CASE WHEN t.completed_at>'2026-02-18T14:30:00+08' THEN NULL ELSE t.completed_at END FROM (SELECT DISTINCT ON(task_id) task_id,to_status,created_at,count(*) OVER(PARTITION BY task_id)::int AS n FROM inspection_log ORDER BY task_id,created_at DESC,id DESC) l WHERE t.id=l.task_id;
DELETE FROM operation_report WHERE report_date>='2026-02-18';
UPDATE operation_report SET created_at=least(created_at,data_as_of);
DELETE FROM marketing_plan WHERE created_at>='2026-02-01';
DELETE FROM flow_forecast;
DELETE FROM forecast_backtest;
DELETE FROM background_job;
UPDATE device SET status_at='2026-02-18T14:20:00+08' WHERE data_mode='DEMO';
UPDATE integration_source SET watermark='2026-02-18T14:20:00+08',last_success_at='2026-02-18T14:20:00+08',last_error_at=NULL,last_error=NULL,health='OK' WHERE data_mode='DEMO';
INSERT INTO integration_source(code,name,source_type,data_mode,provider,health) VALUES ('review-history-2025-11-01-2026-02-18','演示快照回放评价标记','REVIEW','DEMO','历史仿真快照','OK') ON CONFLICT(code) DO NOTHING;
COMMIT;

-- 清除快照中的旧运行记录，保留完整历史库中的原始审计。
DELETE FROM operation_report WHERE provider='fake';
DELETE FROM agent_run;
DELETE FROM ai_usage_daily;
