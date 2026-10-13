package com.snowworld.inspection;

import com.snowworld.demo.DemoSeedContext;
import com.snowworld.demo.DemoSeedStep;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 巡检演示数据: 围绕业务时钟前后生成覆盖全部状态的任务, 结果、异常与日志与状态机保持一致。
 * 仅用于演示, 不代表真实巡检记录。
 */
@Component
public class InspectionDemoSeedStep implements DemoSeedStep {

    private static final Logger log = LoggerFactory.getLogger(InspectionDemoSeedStep.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 景区编码 -> 任务数量 */
    private static final Map<String, Integer> SITE_TASKS = Map.of("BXDSJ", 18, "TYD", 13, "ZYDJ", 9);
    private static final List<String> SITE_ORDER = List.of("BXDSJ", "TYD", "ZYDJ");
    private static final Map<String, String> SITE_ADMINS = Map.of("BXDSJ", "bxdsj_admin", "TYD", "tyd_admin");

    private static final List<String> SCENARIOS = expand(
            "COMPLETED_CLEAN", 9, "COMPLETED_RECTIFIED", 5, "PENDING_REVIEW", 3, "RECTIFYING", 3,
            "RECTIFYING_REJECTED", 2, "SUBMITTED_CLEAN", 3, "SUBMITTED_ISSUE", 2, "IN_PROGRESS", 4,
            "ASSIGNED", 4, "DRAFT", 2, "AGENT_DRAFT", 1, "CANCELLED", 2);

    private static final Map<String, List<String[]>> ISSUES = Map.of(
            "ICE_STRUCTURE", List.<String[]>of(
                    new String[]{"ICE_CRACK", "冰塔底座西侧出现约 3mm 贯穿裂缝", "HIGH", "已加设围挡并由施工班组注水补冰加固"},
                    new String[]{"ICE_MELT", "向阳面冰墙表面融化滴水，地面形成薄冰", "MEDIUM", "已铲除薄冰并铺设防滑垫，调整灯具距离"},
                    new String[]{"ANTISLIP", "坡道防滑垫移位，局部裸露冰面", "MEDIUM", "已重新固定防滑垫并撒布防滑颗粒"}),
            "RIDE", List.<String[]>of(
                    new String[]{"SLIDE_SURFACE", "冰滑梯中段滑道出现坑洼", "HIGH", "已暂停该滑道，补冰打磨后恢复开放"},
                    new String[]{"SLIDE_BUFFER", "终点缓冲垫移位未及时复位", "MEDIUM", "已复位并增加固定桩"},
                    new String[]{"QUEUE_ORDER", "排队区隔离栏缺失一段，出现插队拥挤", "LOW", "已补齐隔离栏并增派引导员"}),
            "FIRE", List.<String[]>of(
                    new String[]{"EXIT_CLEAR", "应急通道被清雪堆占用约 2 米", "HIGH", "已清运积雪，通道恢复畅通"},
                    new String[]{"EXTINGUISHER", "一具灭火器压力指针低于绿区", "MEDIUM", "已更换合格灭火器并登记"},
                    new String[]{"EXIT_SIGN", "疏散指示灯一处不亮", "LOW", "已更换灯具，测试正常"}),
            "SERVICE", List.<String[]>of(
                    new String[]{"TOILET", "卫生间地面积水结冰，存在滑倒风险", "MEDIUM", "已除冰并铺设吸水防滑垫，增加保洁频次"},
                    new String[]{"WARMING", "取暖点一台暖风机不工作，室内温度偏低", "MEDIUM", "已更换暖风机，室温恢复"},
                    new String[]{"TRASH", "主路垃圾桶满溢", "LOW", "已清运并调整清运班次"}),
            "VIDEO", List.<String[]>of(
                    new String[]{"CAM_IMAGE", "镜头结霜，画面模糊", "MEDIUM", "已除霜并开启防护罩加热"},
                    new String[]{"CAM_ANGLE", "新增冰雕遮挡监控视野", "LOW", "已调整云台角度，覆盖恢复"},
                    new String[]{"CAM_POWER", "线缆外皮被踩压破损", "HIGH", "已更换线缆并加装过线槽"}));

    private record Template(long id, String name, String category, List<String[]> items) {
    }

    @Override
    public int order() {
        return 300;
    }

    @Override
    public String name() {
        return "巡检任务";
    }

    @Override
    public boolean alreadySeeded(DemoSeedContext ctx) {
        return ctx.jdbc().sql("SELECT count(*) FROM inspection_task").query(Long.class).single() > 0;
    }

    @Override
    public void seed(DemoSeedContext ctx) {
        JdbcClient jdbc = ctx.jdbc();
        Random rnd = ctx.random();
        List<Template> templates = loadTemplates(jdbc);
        List<Long> inspectors = new ArrayList<>();
        user(jdbc, "inspector1").ifPresent(inspectors::add);
        user(jdbc, "inspector2").ifPresent(inspectors::add);
        if (templates.isEmpty() || inspectors.isEmpty()) {
            log.warn("缺少巡检模板或巡检员账号, 跳过巡检演示数据");
            return;
        }
        Long admin = user(jdbc, "admin").orElse(null);
        OffsetDateTime now = OffsetDateTime.now(ctx.clock());
        Map<String, Integer> seqByDate = new HashMap<>();
        int index = 0;
        int created = 0;
        for (String code : SITE_ORDER) {
            Optional<Long> siteId = jdbc.sql("SELECT id FROM scenic_site WHERE code = ?").param(code)
                    .query(Long.class).optional();
            int count = SITE_TASKS.get(code);
            if (siteId.isEmpty()) {
                index += count;
                continue;
            }
            List<Map<String, Object>> zones = jdbc.sql("SELECT id, name FROM scenic_zone WHERE site_id = ? AND active ORDER BY id")
                    .param(siteId.get()).query().listOfRows();
            Long creator = user(jdbc, SITE_ADMINS.getOrDefault(code, "admin")).orElse(admin);
            Long reviewer = user(jdbc, "reviewer").orElse(creator);
            for (int i = 0; i < count; i++, index++) {
                // 按步长取场景, 让每个景区都覆盖多种状态
                String scenario = SCENARIOS.get((index * 7) % SCENARIOS.size());
                Template template = templates.get(rnd.nextInt(templates.size()));
                Map<String, Object> zone = zones.isEmpty() ? null : zones.get(rnd.nextInt(zones.size()));
                seedTask(jdbc, rnd, now, seqByDate, siteId.get(), zone, template, scenario,
                        inspectors.get(rnd.nextInt(inspectors.size())), reviewer, creator);
                created++;
            }
        }
        log.info("生成巡检演示任务 {} 条", created);
    }

    private void seedTask(JdbcClient jdbc, Random rnd, OffsetDateTime now, Map<String, Integer> seqByDate, long siteId,
                          Map<String, Object> zone, Template template, String scenario, long inspector,
                          Long reviewer, Long creator) {
        int daysAgo = switch (scenario) {
            case "COMPLETED_CLEAN", "COMPLETED_RECTIFIED" -> 1 + rnd.nextInt(10);
            case "PENDING_REVIEW", "RECTIFYING", "RECTIFYING_REJECTED" -> 1 + rnd.nextInt(4);
            case "SUBMITTED_CLEAN", "SUBMITTED_ISSUE" -> rnd.nextInt(2);
            case "IN_PROGRESS" -> rnd.nextInt(3);
            case "ASSIGNED" -> rnd.nextInt(4);
            case "CANCELLED" -> 3 + rnd.nextInt(6);
            default -> 0;
        };
        // 当天的任务步长较短, 保证所有时间点都不晚于业务当前时间
        int maxStep = daysAgo == 0 ? 45 : 180;
        OffsetDateTime[] cursor = {daysAgo == 0 ? now.minusHours(7) : now.minusDays(daysAgo).minusHours(rnd.nextInt(6))};
        OffsetDateTime createdAt = cursor[0];

        boolean agentDraft = "AGENT_DRAFT".equals(scenario);
        boolean hasIssue = switch (scenario) {
            case "COMPLETED_RECTIFIED", "PENDING_REVIEW", "RECTIFYING", "RECTIFYING_REJECTED", "SUBMITTED_ISSUE" -> true;
            default -> false;
        };
        String finalStatus = switch (scenario) {
            case "COMPLETED_CLEAN", "COMPLETED_RECTIFIED" -> "COMPLETED";
            case "RECTIFYING", "RECTIFYING_REJECTED" -> "RECTIFYING";
            case "SUBMITTED_CLEAN", "SUBMITTED_ISSUE" -> "SUBMITTED";
            case "AGENT_DRAFT" -> "DRAFT";
            default -> scenario;
        };
        boolean assigned = !"DRAFT".equals(finalStatus) && !("CANCELLED".equals(finalStatus) && rnd.nextBoolean());
        String zoneName = zone == null ? "" : zone.get("name") + " ";
        String title = agentDraft ? zoneName + "客流高峰时段秩序与防滑专项巡检" : zoneName + template.name();
        String day = createdAt.format(NO_DATE);
        String taskNo = "XJ" + day + "-" + String.format("%04d", seqByDate.merge(day, 1, Integer::sum));
        String[] priorities = {"NORMAL", "NORMAL", "NORMAL", "HIGH", "LOW", "URGENT"};

        long taskId = jdbc.sql("""
                        INSERT INTO inspection_task (task_no, site_id, zone_id, template_id, title, description, priority,
                            assignee_id, reviewer_id, due_at, status, origin, basis_refs, created_by, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?::jsonb, ?, ?, ?)
                        RETURNING id
                        """)
                .params(taskNo, siteId, zone == null ? null : zone.get("id"), template.id(), title,
                        agentDraft ? "AI 运营助手根据区域客流预警与近期负面评价建议开展专项巡检，需人工确认后派发。"
                                : "按日常巡检计划执行，发现异常请拍照并登记。",
                        priorities[rnd.nextInt(priorities.length)], assigned ? inspector : null,
                        assigned ? reviewer : null, "DRAFT".equals(finalStatus) ? null : createdAt.plusHours(24),
                        agentDraft ? "AGENT_DRAFT" : "MANUAL",
                        agentDraft ? "[\"flow:zone-load\",\"review:summary\"]" : null,
                        creator, createdAt, createdAt)
                .query(Long.class).single();

        int version = 0;
        String status = "DRAFT";
        addLog(jdbc, taskId, agentDraft ? "AGENT_DRAFT" : "CREATE", null, "DRAFT", creator,
                agentDraft ? "AI 运营助手创建草稿" : null, createdAt);

        List<String[]> path = new ArrayList<>();
        switch (scenario) {
            case "ASSIGNED" -> path.add(step("ASSIGN", "ASSIGNED"));
            case "IN_PROGRESS" -> addAll(path, step("ASSIGN", "ASSIGNED"), step("START", "IN_PROGRESS"));
            case "SUBMITTED_CLEAN", "SUBMITTED_ISSUE" ->
                    addAll(path, step("ASSIGN", "ASSIGNED"), step("START", "IN_PROGRESS"), step("SUBMIT", "SUBMITTED"));
            case "COMPLETED_CLEAN" -> addAll(path, step("ASSIGN", "ASSIGNED"), step("START", "IN_PROGRESS"),
                    step("SUBMIT", "SUBMITTED"), step("CONFIRM", "COMPLETED"));
            case "RECTIFYING" -> addAll(path, step("ASSIGN", "ASSIGNED"), step("START", "IN_PROGRESS"),
                    step("SUBMIT", "SUBMITTED"), step("REQUIRE_RECTIFY", "RECTIFYING"));
            case "PENDING_REVIEW" -> addAll(path, step("ASSIGN", "ASSIGNED"), step("START", "IN_PROGRESS"),
                    step("SUBMIT", "SUBMITTED"), step("REQUIRE_RECTIFY", "RECTIFYING"),
                    step("RECTIFY_DONE", "PENDING_REVIEW"));
            case "RECTIFYING_REJECTED" -> addAll(path, step("ASSIGN", "ASSIGNED"), step("START", "IN_PROGRESS"),
                    step("SUBMIT", "SUBMITTED"), step("REQUIRE_RECTIFY", "RECTIFYING"),
                    step("RECTIFY_DONE", "PENDING_REVIEW"), step("REJECT", "RECTIFYING"));
            case "COMPLETED_RECTIFIED" -> addAll(path, step("ASSIGN", "ASSIGNED"), step("START", "IN_PROGRESS"),
                    step("SUBMIT", "SUBMITTED"), step("REQUIRE_RECTIFY", "RECTIFYING"),
                    step("RECTIFY_DONE", "PENDING_REVIEW"), step("APPROVE", "COMPLETED"));
            case "CANCELLED" -> {
                if (assigned) {
                    path.add(step("ASSIGN", "ASSIGNED"));
                }
                path.add(step("CANCEL", "CANCELLED"));
            }
            default -> {
                // DRAFT / AGENT_DRAFT 保持草稿
            }
        }

        String[] issue = hasIssue ? pick(ISSUES.getOrDefault(template.category(), ISSUES.get("SERVICE")), rnd) : null;
        Long issueId = null;
        OffsetDateTime submittedAt = null;
        OffsetDateTime completedAt = null;
        for (String[] st : path) {
            cursor[0] = cursor[0].plusMinutes(20 + rnd.nextInt(maxStep - 19));
            OffsetDateTime at = cursor[0];
            String action = st[0];
            String note = null;
            Long actor = switch (action) {
                case "ASSIGN", "CANCEL" -> creator;
                case "START", "SUBMIT", "RECTIFY_DONE" -> inspector;
                default -> reviewer;
            };
            switch (action) {
                case "START" -> {
                    // 执行中的任务只填写了部分检查项
                    boolean partial = "IN_PROGRESS".equals(finalStatus);
                    int limit = partial ? Math.max(1, template.items().size() / 2) : template.items().size();
                    for (int k = 0; k < limit; k++) {
                        String[] item = template.items().get(k);
                        boolean abnormal = issue != null && issue[0].equals(item[0]);
                        jdbc.sql("""
                                        INSERT INTO inspection_item_result (task_id, item_code, item_name, result, remark, created_at)
                                        VALUES (?, ?, ?, ?, ?, ?)
                                        """)
                                .params(taskId, item[0], item[1], abnormal ? "ABNORMAL" : "OK",
                                        abnormal ? issue[1] : null, at.plusMinutes(5))
                                .update();
                    }
                    if (issue != null) {
                        issueId = jdbc.sql("""
                                        INSERT INTO inspection_issue (task_id, item_code, description, severity, status, created_at, updated_at)
                                        VALUES (?, ?, ?, ?, 'OPEN', ?, ?) RETURNING id
                                        """)
                                .params(taskId, issue[0], issue[1], issue[2], at.plusMinutes(5), at.plusMinutes(5))
                                .query(Long.class).single();
                    }
                }
                case "SUBMIT" -> submittedAt = at;
                case "REQUIRE_RECTIFY" -> {
                    note = "请于今日闭园前完成整改并拍照回传";
                    jdbc.sql("UPDATE inspection_issue SET rectifier_id = ?, updated_at = ? WHERE id = ?")
                            .params(inspector, at, issueId).update();
                }
                case "RECTIFY_DONE" -> {
                    note = issue[3];
                    jdbc.sql("""
                                    UPDATE inspection_issue SET status = 'RECTIFIED', rectifier_id = ?, rectify_note = ?, updated_at = ?
                                    WHERE id = ?
                                    """)
                            .params(inspector, issue[3], at, issueId).update();
                }
                case "REJECT" -> {
                    note = "整改不到位，现场仍存在隐患，请重新处理";
                    jdbc.sql("UPDATE inspection_issue SET status = 'OPEN', review_note = ?, updated_at = ? WHERE id = ?")
                            .params(note, at, issueId).update();
                }
                case "APPROVE" -> {
                    note = "复核通过";
                    completedAt = at;
                    jdbc.sql("UPDATE inspection_issue SET status = 'CLOSED', review_note = ?, updated_at = ? WHERE id = ?")
                            .params(note, at, issueId).update();
                }
                case "CONFIRM" -> completedAt = at;
                case "CANCEL" -> note = "计划调整，任务取消";
                default -> {
                }
            }
            addLog(jdbc, taskId, action, status, st[1], actor, note, at);
            status = st[1];
            version++;
        }
        jdbc.sql("""
                        UPDATE inspection_task SET status = ?, version = ?, updated_at = ?, submitted_at = ?, completed_at = ?
                        WHERE id = ?
                        """)
                .params(status, version, cursor[0], submittedAt, completedAt, taskId).update();
    }

    private static void addLog(JdbcClient jdbc, long taskId, String action, String from, String to, Long actor,
                               String note, OffsetDateTime at) {
        jdbc.sql("""
                        INSERT INTO inspection_log (task_id, action, from_status, to_status, actor_id, note, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(taskId, action, from, to, actor, note, at).update();
    }

    private static List<Template> loadTemplates(JdbcClient jdbc) {
        List<Template> out = new ArrayList<>();
        for (Map<String, Object> row : jdbc.sql("SELECT id, name, category, items::text AS items FROM inspection_template WHERE active ORDER BY id")
                .query().listOfRows()) {
            List<String[]> items = new ArrayList<>();
            JsonNode node = JSON.readTree((String) row.get("items"));
            node.forEach(n -> items.add(new String[]{n.path("code").asString(), n.path("name").asString()}));
            if (!items.isEmpty()) {
                out.add(new Template(((Number) row.get("id")).longValue(), (String) row.get("name"),
                        (String) row.get("category"), items));
            }
        }
        return out;
    }

    private static Optional<Long> user(JdbcClient jdbc, String username) {
        return jdbc.sql("SELECT id FROM app_user WHERE username = ?").param(username).query(Long.class).optional();
    }

    private static String[] step(String action, String to) {
        return new String[]{action, to};
    }

    private static void addAll(List<String[]> path, String[]... steps) {
        path.addAll(List.of(steps));
    }

    private static String[] pick(List<String[]> list, Random rnd) {
        return list.get(rnd.nextInt(list.size()));
    }

    private static List<String> expand(Object... pairs) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            for (int k = 0; k < (Integer) pairs[i + 1]; k++) {
                out.add((String) pairs[i]);
            }
        }
        return out;
    }
}
