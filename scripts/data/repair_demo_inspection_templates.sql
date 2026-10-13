-- 只调整自动生成、尚无人为变更的计划样例，使模板符合景区业态和开放季。
BEGIN;
CREATE TEMP TABLE demo_template_repairs ON COMMIT DROP AS
SELECT t.id,t.template_id AS previous_template_id,tpl.id AS template_id,tpl.name,tpl.items
FROM inspection_task t JOIN scenic_site s ON s.id=t.site_id
JOIN scenic_zone z ON z.id=t.zone_id JOIN inspection_template old ON old.id=t.template_id
JOIN LATERAL (SELECT id,name,items FROM inspection_template WHERE active AND category='SERVICE' ORDER BY id LIMIT 1) tpl ON true
WHERE t.idempotency_key LIKE 'demo-schedule-v1:%'
 AND t.version=(SELECT count(*)-1 FROM inspection_log l WHERE l.task_id=t.id)
 AND old.category IN ('ICE_STRUCTURE','RIDE')
 AND (s.code<>'BXDSJ'
   OR NOT(to_char(t.created_at AT TIME ZONE 'Asia/Shanghai','MM-DD')>='12-20' OR to_char(t.created_at AT TIME ZONE 'Asia/Shanghai','MM-DD')<='02-28')
   OR (old.category='RIDE' AND z.name NOT LIKE '%滑梯%' AND z.name NOT LIKE '%摩天轮%'));
UPDATE inspection_task t SET template_id=r.template_id,
 title=z.name||' '||r.name||coalesce(substring(t.title FROM '（[^）]*）$'),'（计划演示）')
FROM demo_template_repairs r,scenic_zone z WHERE t.id=r.id AND z.id=t.zone_id;
DELETE FROM inspection_item_result result USING demo_template_repairs r WHERE result.task_id=r.id;
UPDATE inspection_issue i SET item_code=r.items->0->>'code',
 description='服务设施检查发现异常，需按检查标准检修并登记复测结果。',
 rectify_note=CASE WHEN i.status IN ('RECTIFIED','CLOSED') THEN '已完成设施检修，现场复测正常。' ELSE i.rectify_note END
FROM demo_template_repairs r WHERE i.task_id=r.id;
UPDATE inspection_log l SET note=CASE WHEN l.action='RECTIFY_DONE' THEN '已完成设施检修，现场复测正常。' ELSE l.note END
FROM demo_template_repairs r WHERE l.task_id=r.id;
INSERT INTO inspection_item_result(task_id,item_code,item_name,result,remark,created_at)
SELECT t.id,item->>'code',item->>'name',
 CASE WHEN item->>'code'=r.items->0->>'code' AND EXISTS(SELECT 1 FROM inspection_issue WHERE task_id=t.id) THEN 'ABNORMAL' ELSE 'OK' END,
 CASE WHEN item->>'code'=r.items->0->>'code' AND EXISTS(SELECT 1 FROM inspection_issue WHERE task_id=t.id)
   THEN '服务设施检查发现异常，需按检查标准检修并登记复测结果。' ELSE NULL END,
 least(t.updated_at,t.created_at+interval '1 hour')
FROM demo_template_repairs r JOIN inspection_task t ON t.id=r.id
CROSS JOIN LATERAL jsonb_array_elements(r.items) WITH ORDINALITY AS data(item,n)
WHERE t.status IN ('IN_PROGRESS','SUBMITTED','RECTIFYING','PENDING_REVIEW','COMPLETED')
 AND (t.status<>'IN_PROGRESS' OR n<=greatest(1,jsonb_array_length(r.items)/2));
SELECT count(*) AS corrected_schedule_templates FROM demo_template_repairs;
COMMIT;
