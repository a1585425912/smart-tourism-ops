package com.snowworld.review;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.snowworld.common.DataMeta;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** 网评接口 DTO, 字段与 docs/api-contract.md §6 一致。 */
public final class ReviewDtos {

    private ReviewDtos() {
    }

    public record TopicItem(String code, String name) {
    }

    public record AnalysisView(int version, String origin, String sentiment, List<String> topics, String summary,
                               boolean needsHumanReview, JsonNode evidenceSpans, String model, String promptVersion,
                               String createdByName, String note, OffsetDateTime createdAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ReviewView(long id, long siteId, String siteName, String platform, String authorMasked,
                             BigDecimal rating, String contentMasked, OffsetDateTime publishedAt, boolean isComplaint,
                             String dataMode, String analysisStatus, AnalysisView analysis) {
    }

    public record ReviewDetail(long id, long siteId, String siteName, String platform, String authorMasked,
                               BigDecimal rating, String contentMasked, OffsetDateTime publishedAt, boolean isComplaint,
                               String dataMode, String analysisStatus, AnalysisView analysis, List<AnalysisView> history,
                               String sourceRecordId, OffsetDateTime importedAt, String importBatch) {
    }

    public record ClassificationRequest(String sentiment, List<String> topics, String summary, String note,
                                        Integer expectedVersion) {
    }

    public record TopicCount(String topic, String name, long count, long negative) {
    }

    public record PlatformCount(String platform, long count) {
    }

    public record DayCount(LocalDate date, long total, long negative) {
    }

    public record SampleText(long reviewId, String text) {
    }

    public record HotIssue(String topic, String name, long count, List<SampleText> samples) {
    }

    public record ReviewSummary(Long siteId, OffsetDateTime start, OffsetDateTime end, long total, BigDecimal avgRating,
                                long complaintCount, Map<String, Long> bySentiment, List<TopicCount> byTopic,
                                List<PlatformCount> byPlatform, List<DayCount> daily, List<HotIssue> hotIssues,
                                DataMeta meta) {
    }

    public record ImportError(int line, String message) {
    }

    public record ImportResult(String batchId, int total, int inserted, int duplicates, List<ImportError> errors) {
    }

    public record AnalysisJobRequest(Long siteId, List<Long> reviewIds, Integer limit) {
    }
}
