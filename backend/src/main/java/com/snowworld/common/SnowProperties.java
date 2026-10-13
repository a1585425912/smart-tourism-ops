package com.snowworld.common;

import java.time.OffsetDateTime;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "snow")
public record SnowProperties(
        @DefaultValue("../data") String dataDir,
        @DefaultValue("../runtime/storage") String storageDir,
        @DefaultValue Demo demo,
        @DefaultValue Ai ai,
        @DefaultValue Video video) {

    public record Demo(
            @DefaultValue("false") boolean seed,
            OffsetDateTime replayAt,
            @DefaultValue("2024-01-01") String historyFrom,
            String userPassword) {
    }

    public record Ai(
            @DefaultValue("fake") String provider,
            @DefaultValue("https://api.deepseek.com") String baseUrl,
            String apiKey,
            @DefaultValue("deepseek-flash") String model,
            @DefaultValue("6") int maxModelCalls,
            @DefaultValue("12") int maxToolCalls,
            @DefaultValue("120") int runTimeoutSeconds,
            @DefaultValue("60") int requestTimeoutSeconds,
            @DefaultValue("50") int dailyRunsPerUser,
            @DefaultValue("400000") int dailyTokensPerUser,
            @DefaultValue("20") int reviewBatchSize) {

        public boolean deepseekEnabled() {
            return "deepseek".equalsIgnoreCase(provider) && apiKey != null && !apiKey.isBlank();
        }
    }

    public record Video(
            @DefaultValue("demo") String provider,
            @DefaultValue("120") int sessionTtlSeconds) {
    }
}
