package com.snowworld.review;

import com.snowworld.audit.AuditService;
import com.snowworld.auth.AuthUser;
import com.snowworld.auth.ScopeService;
import com.snowworld.common.ApiException;
import com.snowworld.common.DataMeta;
import com.snowworld.common.ErrorCode;
import com.snowworld.common.PageResult;
import com.snowworld.review.ReviewDtos.AnalysisView;
import com.snowworld.review.ReviewDtos.ClassificationRequest;
import com.snowworld.review.ReviewDtos.DayCount;
import com.snowworld.review.ReviewDtos.HotIssue;
import com.snowworld.review.ReviewDtos.ImportError;
import com.snowworld.review.ReviewDtos.ImportResult;
import com.snowworld.review.ReviewDtos.PlatformCount;
import com.snowworld.review.ReviewDtos.ReviewDetail;
import com.snowworld.review.ReviewDtos.ReviewSummary;
import com.snowworld.review.ReviewDtos.ReviewView;
import com.snowworld.review.ReviewDtos.SampleText;
import com.snowworld.review.ReviewDtos.TopicCount;
import com.snowworld.scenic.SiteMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ReviewService {

    public static final String CSV_SOURCE = "review-csv-import";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> SENTIMENTS = Set.of("POSITIVE", "NEUTRAL", "NEGATIVE", "MIXED", "UNKNOWN");
    private static final List<String> CSV_HEADER = List.of("source_record_id", "site_code", "platform", "published_at",
            "rating", "content", "author", "is_complaint");
    private static final int MAX_IMPORT_ROWS = 20000;

    /** 模型或规则分析结果, 写入前已完成字典与证据校验。 */
    public record AnalysisInput(String sentiment, List<String> topics, String summary, JsonNode evidenceSpans,
                                boolean needsHumanReview, String model, String promptVersion, Long agentRunId) {
    }

    private final ReviewMapper mapper;
    private final SiteMapper siteMapper;
    private final ScopeService scope;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final Clock clock;

    public ReviewService(ReviewMapper mapper, SiteMapper siteMapper, ScopeService scope, AuditService audit,
                         JdbcClient jdbc, Clock clock) {
        this.mapper = mapper;
        this.siteMapper = siteMapper;
        this.scope = scope;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public List<ReviewDtos.TopicItem> topics() {
        return Arrays.stream(ReviewTopic.values()).map(t -> new ReviewDtos.TopicItem(t.name(), t.label())).toList();
    }

    public PageResult<ReviewView> page(List<Long> siteIds, String sentiment, String topic, String keyword, String status,
                                       Boolean complaint, OffsetDateTime start, OffsetDateTime end, Integer page, Integer size) {
        List<Long> sites = scope.resolve(siteIds);
        int p = PageResult.clampPage(page);
        int s = PageResult.clampSize(size);
        if (sites.isEmpty()) {
            return new PageResult<>(List.of(), 0, p, s);
        }
        ReviewMapper.Filter f = new ReviewMapper.Filter();
        f.setSiteIds(sites);
        f.setSentiment(blankToNull(sentiment));
        f.setTopic(blankToNull(topic));
        f.setKeyword(keyword == null || keyword.isBlank() ? null : keyword.strip().replace("%", "").replace("_", ""));
        f.setStatus(blankToNull(status));
        f.setComplaint(complaint);
        f.setStart(start);
        f.setEnd(end);
        f.setLimit(s);
        f.setOffset(PageResult.offset(p, s));
        List<ReviewView> items = mapper.list(f).stream().map(ReviewService::view).toList();
        return new PageResult<>(items, mapper.count(f), p, s);
    }

    public ReviewDetail detail(long id) {
        ReviewMapper.ReviewRow row = requireReview(id);
        List<AnalysisView> history = mapper.history(id).stream().map(ReviewService::view).toList();
        ReviewView v = view(row);
        return new ReviewDetail(v.id(), v.siteId(), v.siteName(), v.platform(), v.authorMasked(), v.rating(),
                v.contentMasked(), v.publishedAt(), v.isComplaint(), v.dataMode(), v.analysisStatus(), v.analysis(),
                history, row.getSourceRecordId(), row.getImportedAt(), row.getImportBatch());
    }

    /** 人工修正: 生成 HUMAN 新版本; 后续模型批量分析不会覆盖。 */
    @Transactional
    public ReviewDetail classify(long id, ClassificationRequest req) {
        ReviewMapper.ReviewRow row = requireReview(id);
        if (req.sentiment() == null || !SENTIMENTS.contains(req.sentiment())) {
            throw ApiException.badRequest("sentiment 不合法");
        }
        if (req.topics() == null || req.topics().isEmpty() || req.topics().size() > 5) {
            throw ApiException.badRequest("topics 需要 1-5 个");
        }
        List<String> topics = new ArrayList<>();
        for (String t : req.topics()) {
            topics.add(ReviewTopic.parse(t).orElseThrow(() -> ApiException.badRequest("未知主题: " + t)).name());
        }
        if (req.expectedVersion() == null) {
            throw ApiException.badRequest("需要提供 expectedVersion");
        }
        mapper.lockStatus(id);
        Integer current = mapper.currentVersion(id);
        if ((current == null ? 0 : current) != req.expectedVersion()) {
            throw new ApiException(ErrorCode.CONFLICT, "该评价的分类已被更新，请刷新后重试");
        }
        AuthUser user = ScopeService.currentUser();
        ReviewMapper.AnalysisRow a = new ReviewMapper.AnalysisRow();
        a.setReviewId(id);
        a.setVersion(mapper.maxVersion(id) + 1);
        a.setOrigin("HUMAN");
        a.setSentiment(req.sentiment());
        a.setTopics(topics.stream().distinct().toArray(String[]::new));
        a.setSummary(truncate(req.summary(), 300));
        a.setEvidenceSpans(JSON.createArrayNode());
        a.setNeedsHumanReview(false);
        a.setCreatedBy(user.getId());
        a.setNote(truncate(req.note(), 300));
        mapper.clearCurrent(id);
        mapper.insertAnalysis(a);
        mapper.updateStatus(id, "HUMAN");
        audit.record("REVIEW_CLASSIFY", "REVIEW", String.valueOf(id), row.getSiteId(), "OK",
                req.sentiment() + " " + topics);
        return detail(id);
    }

    /**
     * 保存模型分析结果。若评价已有人工版本则跳过(返回 false), 保证人工修正不被后台重跑静默覆盖。
     */
    @Transactional
    public boolean saveModelAnalysis(long reviewId, AnalysisInput in) {
        String status = mapper.lockStatus(reviewId);
        if (status == null || "HUMAN".equals(status)) {
            return false;
        }
        ReviewMapper.AnalysisRow a = new ReviewMapper.AnalysisRow();
        a.setReviewId(reviewId);
        a.setVersion(mapper.maxVersion(reviewId) + 1);
        a.setOrigin("MODEL");
        a.setSentiment(in.sentiment());
        a.setTopics(in.topics().toArray(String[]::new));
        a.setSummary(truncate(in.summary(), 300));
        a.setEvidenceSpans(in.evidenceSpans() == null ? JSON.createArrayNode() : in.evidenceSpans());
        a.setNeedsHumanReview(in.needsHumanReview());
        a.setModel(in.model());
        a.setPromptVersion(in.promptVersion());
        a.setAgentRunId(in.agentRunId());
        mapper.clearCurrent(reviewId);
        mapper.insertAnalysis(a);
        mapper.updateStatus(reviewId, "ANALYZED");
        return true;
    }

    public void markFailed(long reviewId) {
        jdbc.sql("UPDATE review SET analysis_status = 'FAILED' WHERE id = ? AND analysis_status = 'PENDING'")
                .param(reviewId).update();
    }

    public ReviewSummary summary(Long siteId, OffsetDateTime start, OffsetDateTime end) {
        List<Long> sites = scope.resolve(siteId == null ? null : List.of(siteId));
        OffsetDateTime[] range = defaultRange(start, end);
        return summaryFor(siteId, sites, range[0], range[1]);
    }

    /** 不做权限判断的汇总, 调用方(Agent 工具、日报)须已校验 sites。 */
    public ReviewSummary summaryFor(Long siteId, List<Long> sites, OffsetDateTime start, OffsetDateTime end) {
        if (sites.isEmpty()) {
            return new ReviewSummary(siteId, start, end, 0, null, 0, Map.of(), List.of(), List.of(), List.of(), List.of(),
                    DataMeta.missing(List.of(), "DEMO"));
        }
        ReviewMapper.Totals totals = mapper.totals(sites, start, end);
        Map<String, Long> bySentiment = new LinkedHashMap<>();
        mapper.bySentiment(sites, start, end).forEach(k -> bySentiment.put(k.getKey(), k.getCnt()));
        List<TopicCount> byTopic = mapper.byTopic(sites, start, end).stream()
                .map(k -> new TopicCount(k.getKey(), ReviewTopic.labelOf(k.getKey()), k.getCnt(), nz(k.getNegative())))
                .toList();
        List<PlatformCount> byPlatform = mapper.byPlatform(sites, start, end).stream()
                .map(k -> new PlatformCount(k.getKey(), k.getCnt())).toList();
        List<DayCount> daily = mapper.daily(sites, start, end).stream()
                .map(d -> new DayCount(d.getDay(), d.getTotal(), nz(d.getNegative()))).toList();
        List<HotIssue> hot = byTopic.stream()
                .filter(t -> t.negative() > 0 && !"OTHER".equals(t.topic()))
                .sorted((a, b) -> Long.compare(b.negative(), a.negative()))
                .limit(3)
                .map(t -> new HotIssue(t.topic(), t.name(), t.negative(),
                        mapper.negativeSamples(sites, start, end, t.topic(), 2).stream()
                                .map(sm -> new SampleText(sm.getReviewId(), truncate(sm.getText(), 120))).toList()))
                .toList();
        long total = totals.getTotal() == null ? 0 : totals.getTotal();
        DataMeta meta = total == 0
                ? DataMeta.missing(mapper.sourceCodes(sites, start, end), "DEMO")
                : new DataMeta(totals.getLatest(), mapper.sourceCodes(sites, start, end),
                DataMeta.mergeMode(mapper.dataModes(sites, start, end)), "GOOD");
        return new ReviewSummary(siteId, start, end, total, totals.getAvgRating(), nz(totals.getComplaints()),
                bySentiment, byTopic, byPlatform, daily, hot, meta);
    }

    /** 读取已授权评价的脱敏原文片段; 不在 sites 范围内的 ID 不返回。 */
    public List<ReviewMapper.ReviewRow> evidence(List<Long> ids, List<Long> sites) {
        if (ids.isEmpty() || sites.isEmpty()) {
            return List.of();
        }
        return mapper.findByIds(ids).stream().filter(r -> sites.contains(r.getSiteId())).toList();
    }

    public List<Long> pendingIds(List<Long> sites, int limit) {
        return sites.isEmpty() ? List.of() : mapper.pendingIds(sites, limit);
    }

    public ReviewMapper.ReviewRow find(long id) {
        return mapper.findById(id);
    }

    // ---------------- CSV 导入 ----------------

    public ImportResult importCsv(byte[] bytes) {
        AuthUser user = ScopeService.currentUser();
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        if (text.indexOf('\uFFFD') >= 0) {
            throw new ApiException(ErrorCode.IMPORT_FAILED, "文件不是有效的 UTF-8 编码");
        }
        List<CsvParser.Row> rows = CsvParser.parse(text);
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.IMPORT_FAILED, "文件为空");
        }
        List<String> header = rows.getFirst().fields().stream().map(h -> h.strip().toLowerCase()).toList();
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            col.put(header.get(i), i);
        }
        List<String> missing = CSV_HEADER.stream()
                .filter(h -> !Set.of("author", "is_complaint", "rating").contains(h) && !col.containsKey(h)).toList();
        if (!missing.isEmpty()) {
            throw new ApiException(ErrorCode.IMPORT_FAILED, "缺少必填列: " + String.join(", ", missing),
                    Map.of("expectedHeader", String.join(",", CSV_HEADER)));
        }
        if (rows.size() - 1 > MAX_IMPORT_ROWS) {
            throw new ApiException(ErrorCode.IMPORT_FAILED, "单次最多导入 " + MAX_IMPORT_ROWS + " 行");
        }
        Map<String, Long> siteByCode = new HashMap<>();
        siteMapper.findAll().forEach(s -> siteByCode.put(s.getCode().toUpperCase(), s.getId()));
        List<Long> allowed = scope.allowedSiteIds(user.getId());
        long sourceId = ensureCsvSource();
        String batch = "R" + OffsetDateTime.now(clock).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "-"
                + UUID.randomUUID().toString().substring(0, 6);
        List<ImportError> errors = new ArrayList<>();
        int inserted = 0;
        int duplicates = 0;
        Set<Long> touchedSites = new LinkedHashSet<>();
        for (CsvParser.Row row : rows.subList(1, rows.size())) {
            try {
                ReviewMapper.NewReview r = toReview(row, col, siteByCode, allowed);
                r.setSourceId(sourceId);
                r.setImportBatch(batch);
                r.setDataMode("REAL");
                if (mapper.insert(r) == 1) {
                    inserted++;
                    touchedSites.add(r.getSiteId());
                } else {
                    duplicates++;
                }
            } catch (IllegalArgumentException e) {
                errors.add(new ImportError(row.line(), e.getMessage()));
            }
        }
        jdbc.sql("""
                UPDATE integration_source SET last_success_at = now(), health = 'OK',
                    watermark = greatest(coalesce(watermark, now()), now()) WHERE id = ?
                """).param(sourceId).update();
        audit.record("REVIEW_IMPORT", "REVIEW_BATCH", batch, touchedSites.size() == 1 ? touchedSites.iterator().next() : null,
                errors.isEmpty() ? "OK" : "PARTIAL", "inserted=" + inserted + " duplicates=" + duplicates + " errors=" + errors.size());
        return new ImportResult(batch, rows.size() - 1, inserted, duplicates, errors.stream().limit(500).toList());
    }

    private ReviewMapper.NewReview toReview(CsvParser.Row row, Map<String, Integer> col, Map<String, Long> siteByCode,
                                           List<Long> allowed) {
        String recordId = field(row, col, "source_record_id");
        if (recordId == null || recordId.length() > 128) {
            throw new IllegalArgumentException("source_record_id 为空或超过 128 字符");
        }
        String siteCode = field(row, col, "site_code");
        Long siteId = siteCode == null ? null : siteByCode.get(siteCode.toUpperCase());
        if (siteId == null) {
            throw new IllegalArgumentException("未知景区编码: " + siteCode);
        }
        if (!allowed.contains(siteId)) {
            throw new IllegalArgumentException("无权导入该景区数据: " + siteCode);
        }
        String platform = field(row, col, "platform");
        if (platform == null || platform.length() > 32) {
            throw new IllegalArgumentException("platform 为空或超过 32 字符");
        }
        OffsetDateTime published = parseTime(field(row, col, "published_at"));
        String ratingText = field(row, col, "rating");
        BigDecimal rating = null;
        if (ratingText != null) {
            try {
                rating = new BigDecimal(ratingText);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("rating 不是数字: " + ratingText);
            }
            if (rating.signum() < 0 || rating.compareTo(BigDecimal.valueOf(5)) > 0) {
                throw new IllegalArgumentException("rating 须在 0-5 之间");
            }
        }
        String content = field(row, col, "content");
        if (content == null || content.length() > 5000) {
            throw new IllegalArgumentException("content 为空或超过 5000 字符");
        }
        String complaint = field(row, col, "is_complaint");
        ReviewMapper.NewReview r = new ReviewMapper.NewReview();
        r.setSourceRecordId(recordId);
        r.setSiteId(siteId);
        r.setPlatform(platform);
        r.setRating(rating);
        r.setContent(content);
        r.setContentMasked(PiiMasker.mask(content));
        r.setAuthorMasked(PiiMasker.maskName(field(row, col, "author")));
        r.setPublishedAt(published);
        r.setComplaint(complaint != null && Set.of("true", "1", "是", "y", "yes").contains(complaint.toLowerCase()));
        return r;
    }

    private static String field(CsvParser.Row row, Map<String, Integer> col, String name) {
        Integer idx = col.get(name);
        if (idx == null || idx >= row.fields().size()) {
            return null;
        }
        String v = row.fields().get(idx).strip();
        return v.isEmpty() ? null : v;
    }

    static OffsetDateTime parseTime(String s) {
        if (s == null) {
            throw new IllegalArgumentException("published_at 不能为空");
        }
        try {
            return OffsetDateTime.parse(s);
        } catch (DateTimeParseException ignored) {
            // 继续尝试本地时间格式
        }
        for (String p : List.of("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd HH:mm")) {
            try {
                return LocalDateTime.parse(s, DateTimeFormatter.ofPattern(p)).atZone(ZONE).toOffsetDateTime();
            } catch (DateTimeParseException ignored) {
                // 尝试下一种
            }
        }
        try {
            return LocalDate.parse(s).atStartOfDay(ZONE).toOffsetDateTime();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("published_at 时间格式无法识别: " + s);
        }
    }

    private long ensureCsvSource() {
        jdbc.sql("""
                INSERT INTO integration_source (code, name, source_type, data_mode, provider, license_note, health)
                VALUES (?, '网评 CSV 导入', 'REVIEW', 'REAL', '人工导入', '由运营人员导入的评价文件', 'UNKNOWN')
                ON CONFLICT (code) DO NOTHING
                """).param(CSV_SOURCE).update();
        return jdbc.sql("SELECT id FROM integration_source WHERE code = ?").param(CSV_SOURCE).query(Long.class).single();
    }

    // ---------------- 辅助 ----------------

    private ReviewMapper.ReviewRow requireReview(long id) {
        ReviewMapper.ReviewRow row = mapper.findById(id);
        if (row == null) {
            throw ApiException.notFound("评价");
        }
        scope.requireSite(row.getSiteId());
        return row;
    }

    private OffsetDateTime[] defaultRange(OffsetDateTime start, OffsetDateTime end) {
        OffsetDateTime e = end != null ? end
                : LocalDate.now(clock.withZone(ZONE)).plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime s = start != null ? start : e.minusDays(30);
        if (!e.isAfter(s)) {
            throw ApiException.badRequest("end 必须晚于 start");
        }
        if (Duration.between(s, e).toDays() > 400) {
            throw new ApiException(ErrorCode.RANGE_TOO_LARGE, "时间范围不能超过 400 天");
        }
        return new OffsetDateTime[]{s, e};
    }

    static ReviewView view(ReviewMapper.ReviewRow r) {
        AnalysisView a = r.getAnVersion() == null ? null : new AnalysisView(r.getAnVersion(), r.getAnOrigin(),
                r.getAnSentiment(), r.getAnTopics() == null ? List.of() : List.of(r.getAnTopics()), r.getAnSummary(),
                Boolean.TRUE.equals(r.getAnNeedsHuman()), r.getAnEvidence(), r.getAnModel(), r.getAnPromptVersion(),
                r.getAnCreatedByName(), r.getAnNote(), r.getAnCreatedAt());
        return new ReviewView(r.getId(), r.getSiteId(), r.getSiteName(), r.getPlatform(), r.getAuthorMasked(), r.getRating(),
                r.getContentMasked(), r.getPublishedAt(), Boolean.TRUE.equals(r.getIsComplaint()), r.getDataMode(),
                r.getAnalysisStatus(), a);
    }

    static AnalysisView view(ReviewMapper.AnalysisRow a) {
        return new AnalysisView(a.getVersion(), a.getOrigin(), a.getSentiment(),
                a.getTopics() == null ? List.of() : List.of(a.getTopics()), a.getSummary(),
                Boolean.TRUE.equals(a.getNeedsHumanReview()), a.getEvidenceSpans(), a.getModel(), a.getPromptVersion(),
                a.getCreatedByName(), a.getNote(), a.getCreatedAt());
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String truncate(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }
}
