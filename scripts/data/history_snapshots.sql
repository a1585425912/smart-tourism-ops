WITH dates AS (
  SELECT generate_series('2025-11-01'::date,'2026-10-13'::date,'1 day')::date AS d
), flow AS (
  SELECT site_id,(bucket_start AT TIME ZONE 'Asia/Shanghai')::date AS d,
    coalesce(max(sum_value) FILTER(WHERE metric='ENTRY'),0) AS entries,
    coalesce(max(sum_value) FILTER(WHERE metric='EXIT'),0) AS exits,
    coalesce(max(max_value) FILTER(WHERE metric='IN_PARK'),0) AS peak,
    coalesce(max(last_value) FILTER(WHERE metric='IN_PARK'),0) AS in_park
  FROM flow_aggregate WHERE granularity='DAY' AND zone_id=0 GROUP BY 1,2
), business AS (
  SELECT u.site_id,b.biz_date AS d,sum(b.revenue) AS revenue,sum(b.orders) AS orders,
    avg(b.occupancy) FILTER(WHERE u.category='HOTEL') AS occupancy
  FROM business_daily b JOIN business_unit u ON u.id=b.unit_id GROUP BY 1,2
), reviews AS (
  SELECT r.site_id,(r.published_at AT TIME ZONE 'Asia/Shanghai')::date AS d,count(*) AS reviews,
    count(*) FILTER(WHERE a.sentiment='NEGATIVE') AS negative,round(avg(r.rating),2) AS rating
  FROM review r LEFT JOIN review_analysis a ON a.review_id=r.id AND a.is_current GROUP BY 1,2
), groups AS (
  SELECT site_id,visit_date AS d,count(*) AS groups,
    sum(CASE WHEN status IN ('ENTERED','LEFT') THEN entered_count ELSE planned_size END) AS group_visitors
  FROM tour_group WHERE status<>'CANCELLED' GROUP BY 1,2
), inspections AS (
  SELECT t.site_id,(t.created_at AT TIME ZONE 'Asia/Shanghai')::date AS d,count(*) AS tasks,
    count(*) FILTER(WHERE history.to_status='COMPLETED') AS completed,
    count(*) FILTER(WHERE history.to_status IN ('RECTIFYING','PENDING_REVIEW')) AS rectification
  FROM inspection_task t LEFT JOIN LATERAL (
    SELECT l.to_status FROM inspection_log l WHERE l.task_id=t.id
      AND l.created_at < least(date_trunc('day',t.created_at AT TIME ZONE 'Asia/Shanghai') AT TIME ZONE 'Asia/Shanghai' + interval '1 day',now())
    ORDER BY l.created_at DESC,l.id DESC LIMIT 1
  ) history ON true GROUP BY 1,2
)
SELECT coalesce(json_agg(json_build_object(
  'siteId',s.id,'site',s.name,'code',s.code,'date',dates.d,'capacity',s.capacity,
  'closed',s.code='BXDSJ' AND NOT (to_char(dates.d,'MM-DD')>='12-20' OR to_char(dates.d,'MM-DD')<='02-28'),
  'entries',coalesce(f.entries,0),'exits',coalesce(f.exits,0),'peak',coalesce(f.peak,0),'inPark',coalesce(f.in_park,0),
  'revenue',coalesce(b.revenue,0),'orders',coalesce(b.orders,0),'hotelOccupancy',coalesce(b.occupancy,0),
  'reviews',coalesce(r.reviews,0),'negativeReviews',coalesce(r.negative,0),'rating',coalesce(r.rating,0),
  'groups',coalesce(g.groups,0),'groupVisitors',coalesce(g.group_visitors,0),
  'inspectionTasks',coalesce(i.tasks,0),'inspectionCompleted',coalesce(i.completed,0),'rectification',coalesce(i.rectification,0)
) ORDER BY s.id,dates.d),'[]')
FROM scenic_site s CROSS JOIN dates
LEFT JOIN flow f ON f.site_id=s.id AND f.d=dates.d
LEFT JOIN business b ON b.site_id=s.id AND b.d=dates.d
LEFT JOIN reviews r ON r.site_id=s.id AND r.d=dates.d
LEFT JOIN groups g ON g.site_id=s.id AND g.d=dates.d
LEFT JOIN inspections i ON i.site_id=s.id AND i.d=dates.d;
