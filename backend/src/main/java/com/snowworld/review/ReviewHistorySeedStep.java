package com.snowworld.review;

import com.snowworld.demo.DemoFlowModel;
import com.snowworld.demo.DemoSeedContext;
import com.snowworld.demo.DemoSeedStep;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Random;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 仿真游客短评：季节、客流、出游方式、服务主题和表达情绪共同决定内容，始终标为 DEMO。 */
@Component
public class ReviewHistorySeedStep implements DemoSeedStep {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final DemoFlowModel model;
    private record Site(long id, String code) {}
    private static final String[] USERS = {"北方慢旅行", "小鹿看世界", "周末出走", "一家三口", "背包去远方", "江边散步", "南方来的客人", "带爸妈旅行", "小林的相机", "秋日旅记", "吃货在路上", "晨光与远山"};
    private static final String[] OPENINGS = {"和朋友坐地铁来的，", "带着孩子趁工作日来玩，", "陪爸妈慢慢逛，", "跟旅行团过来，", "一个人拿着相机来逛，", "提前看攻略约了同学，", "刚从酒店过来，", "周末和对象来走走，"};
    private static final String[] POSITIVE = {"扫码入园很方便，工作人员指路挺热情", "洗手间干净，标识清楚，带老人走起来也方便", "餐厅上菜挺快，口味不错，份量两个人吃刚好", "志愿者主动帮忙拍合照，很开心", "文创小店挺好逛，挑了个纪念品带回家", "安全员一直在引导，人多但秩序不错", "景色比照片里漂亮，光线好的时候很出片", "提前订好接驳车，到站就能上车，很方便"};
    private static final String[] NEGATIVE = {"排队等了快一个小时，现场提示不清楚，有点失望", "餐饮价格太贵，量也少，希望明码标价更明显", "垃圾桶满了没人及时清理，卫生间排队也太久", "出口打车挺难，等车的地方没有明显标识，不方便", "问路遇到的工作人员态度不好，回应很冷漠", "人太多有点挤，几个路口的引导不合理", "寄存柜不好找，手机没电以后更不方便", "预约退改的说明不清楚，退款一直没人处理"};
    private static final String[] ADVICE = {"建议带个充电宝。", "最好穿舒服的鞋。", "下次打算早点到。", "热门区域可以先绕开。", "老人累了就找地方歇歇。", "别把行程排得太满。", "整体和攻略说的差不多。", "下午的光线更适合拍照。"};

    public ReviewHistorySeedStep(DemoFlowModel model) { this.model = model; }
    @Override public int order() { return 550; }
    @Override public String name() { return "完整历史游客体验仿真评价"; }
    @Override public boolean alreadySeeded(DemoSeedContext ctx) {
        return ctx.jdbc().sql("SELECT EXISTS(SELECT 1 FROM integration_source WHERE code=?)")
                .param(marker(ctx)).query(Boolean.class).single();
    }
    private String marker(DemoSeedContext ctx) { return "review-history-" + ctx.historyFrom() + "-" + LocalDate.now(ctx.clock()); }

    @Override public void seed(DemoSeedContext ctx) {
        long source = ctx.ensureSource("review-history-demo", "历史游客体验仿真评价", "REVIEW", null,
                "季节与客流仿真", "虚构游客身份与评价，平台名称为演示标签", null);
        OffsetDateTime now = OffsetDateTime.now(ctx.clock());
        List<Site> sites = ctx.jdbc().sql("SELECT id,code FROM scenic_site ORDER BY id")
                .query((rs,i) -> new Site(rs.getLong(1), rs.getString(2))).list();
        for (Site site : sites) {
            if (!model.supports(site.code())) continue;
            for (LocalDate day = ctx.historyFrom(); !day.isAfter(now.toLocalDate()); day=day.plusDays(1)) {
                Random rnd = new Random(site.id()*1000003 + day.toEpochDay());
                long visitors = model.dailyEntries(site.code(), day);
                int count = visitors == 0 ? (day.getDayOfWeek().getValue()==3 ? 2 : 0) : (int)Math.max(12, Math.min(60, visitors/900));
                for (int i=0; i<count; i++) {
                    int mood = rnd.nextInt(100);
                    boolean winter = day.getMonthValue()==12 || day.getMonthValue()<=2;
                    String scenery = "BXDSJ".equals(site.code()) ? "晚上的冰雕灯光很震撼"
                            : "TYD".equals(site.code()) ? (winter ? "雪雕作品很壮观" : day.getMonthValue()>=9 ? "太阳湖边的秋色很漂亮" : "湖边绿树和花园很漂亮")
                            : "街上的欧式建筑和江边夜景很漂亮";
                    String content = visitors == 0 ? "查了开放安排，现在是闭园维护期，准备等冰雪季开放再来，希望预约信息更新及时。"
                            : OPENINGS[rnd.nextInt(OPENINGS.length)] + (mood<55 ? scenery+"，"+POSITIVE[rnd.nextInt(POSITIVE.length)]
                              : mood<78 ? NEGATIVE[rnd.nextInt(NEGATIVE.length)]
                              : mood<93 ? scenery+"，但是"+NEGATIVE[rnd.nextInt(NEGATIVE.length)]
                              : "逛了"+(2+rnd.nextInt(4))+"个小时，跟着导览路线走了一圈") + "。" + ADVICE[rnd.nextInt(ADVICE.length)];
                    OffsetDateTime published = day.atTime(9+rnd.nextInt(13), rnd.nextInt(60)).atOffset(now.getOffset());
                    if (published.isAfter(now)) published = now.minusMinutes(i+1);
                    String key = "history-"+site.id()+"-"+day+"-"+i;
                    var inserted = ctx.jdbc().sql("""
                            INSERT INTO review (source_id,source_record_id,site_id,platform,author_masked,rating,content,content_masked,
                              published_at,import_batch,is_complaint,data_mode,analysis_status)
                            VALUES (?,?,?,?,?,?,?,?,?,'history-demo-v1',?,'DEMO','ANALYZED')
                            ON CONFLICT(source_id,source_record_id) DO NOTHING RETURNING id
                            """).params(source,key,site.id(),List.of("携程","美团","小红书","抖音","大众点评").get(rnd.nextInt(5)),
                                    USERS[rnd.nextInt(USERS.length)]+"***"+Integer.toString(i%10), mood<55?4+rnd.nextInt(2):mood<78?1+rnd.nextInt(2):3,
                                    content,content,published,mood>=55&&mood<78&&rnd.nextInt(6)==0).query(Long.class).optional();
                    if (inserted.isEmpty()) continue;
                    var analysis = RuleReviewAnalyzer.analyze(content);
                    var spans = JSON.createArrayNode();
                    analysis.spans().forEach(s -> spans.addObject().put("start",s.start()).put("end",s.end()).put("text",s.text()).put("topic",s.topic()));
                    ctx.jdbc().sql("""
                            INSERT INTO review_analysis (review_id,version,origin,sentiment,topics,summary,evidence_spans,
                              needs_human_review,model,prompt_version,is_current,created_at)
                            VALUES (?,1,'MODEL',?,?::varchar[],?,?::jsonb,?,'local-rule-simulator','history-v1',true,?)
                            """).params(inserted.get(),analysis.sentiment(),"{"+String.join(",",analysis.topics())+"}",analysis.summary(),
                                    JSON.writeValueAsString(spans),analysis.confidence()<.6,published).update();
                }
            }
        }
        ctx.ensureSource(marker(ctx),"评价历史回灌标记","REVIEW",null,"演示适配器","DEMO",null);
    }
}
