package com.snowworld.flow;

import com.snowworld.common.DataMeta;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * 排队、预测、业态、团队、画像等运营洞察的查询契约(实现见本包), 供 API、大屏与 Agent 工具共用。
 * 调用方负责先校验景区范围; 日期区间均为 [start, endExclusive)。
 */
public interface OpsInsightService {

    List<QueueStatus> queues(long siteId);

    DailyForecast dailyForecast(long siteId, int days);

    HourlyForecast hourlyForecast(long siteId, LocalDate date);

    BusinessSummary businessSummary(long siteId, LocalDate start, LocalDate endExclusive);

    GroupStats groupStats(long siteId, LocalDate start, LocalDate endExclusive);

    VisitorProfile visitorProfile(long siteId, YearMonth month);

    record PredictedWait(OffsetDateTime t, BigDecimal waitMin) {
    }

    record QueueStatus(long pointId, String name, String pointType, long siteId, Long zoneId, double lon, double lat,
                       Long queueLength, BigDecimal serviceRatePerMin, BigDecimal estimatedWaitMin,
                       BigDecimal measuredWaitMin, String method, OffsetDateTime at,
                       List<PredictedWait> predicted, DataMeta meta) {
    }

    record Weather(BigDecimal tempMax, BigDecimal tempMin, BigDecimal tempMean, BigDecimal precipitation,
                   BigDecimal snowfall, BigDecimal windMax, Integer weatherCode, boolean isForecast, String source) {
    }

    record ForecastModel(String name, String version, OffsetDateTime trainedAt, List<String> features) {
    }

    record DailyPoint(LocalDate date, BigDecimal predicted, BigDecimal lower, BigDecimal upper, BigDecimal actual,
                      String holiday, boolean isOffDay, Weather weather, List<String> drivers) {
    }

    record Backtest(String modelName, String modelVersion, LocalDate testStart, LocalDate testEnd, int sampleDays,
                    BigDecimal mape, BigDecimal mae, String baselineName, BigDecimal baselineMape, BigDecimal baselineMae) {
    }

    record DailyForecast(long siteId, String siteName, String metric, ForecastModel model, List<DailyPoint> points,
                         Backtest backtest, DataMeta meta) {
    }

    record HourlyPoint(OffsetDateTime t, BigDecimal predictedInPark, BigDecimal predictedEntries) {
    }

    record Peak(OffsetDateTime t, BigDecimal value) {
    }

    record ZonePeak(long zoneId, String zoneName, Integer capacity, BigDecimal peak, OffsetDateTime peakAt,
                    BigDecimal ratio, String level, int staffSuggested) {
    }

    record Recommendation(String level, String title, String detail, Long zoneId) {
    }

    record HourlyForecast(long siteId, LocalDate date, List<HourlyPoint> points, Peak peak, List<ZonePeak> zonePeaks,
                          List<Recommendation> recommendations, DataMeta meta) {
    }

    record CategoryStat(String category, BigDecimal revenue, Long orders, Long guests, int units, BigDecimal avgOccupancy) {
    }

    record DailyCategoryRevenue(LocalDate date, String category, BigDecimal revenue) {
    }

    record UnitRevenue(long unitId, String name, String category, BigDecimal revenue) {
    }

    record BusinessSummary(long siteId, LocalDate start, LocalDate end, String currency, String amountBasis,
                           List<CategoryStat> byCategory, List<DailyCategoryRevenue> daily, List<UnitRevenue> topUnits,
                           DataMeta meta) {
    }

    record AgencyStat(long agencyId, String name, int groups, int visitors) {
    }

    record OriginStat(String region, int groups, int visitors) {
    }

    record DailyGroupStat(LocalDate date, int groups, int visitors) {
    }

    record GroupStats(int totalGroups, int totalVisitors, List<AgencyStat> byAgency, List<OriginStat> byOrigin,
                      List<DailyGroupStat> daily, DataMeta meta) {
    }

    record Bucket(String bucket, int visitors, BigDecimal share) {
    }

    record VisitorProfile(long siteId, String month, int sampleSize, BigDecimal coverage,
                          Map<String, List<Bucket>> dimensions, List<String> notes, DataMeta meta) {
    }
}
