package com.snowworld.common;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 公开的系统运行信息: 是否演示模式、回放时钟、AI 提供方。不包含任何密钥。 */
@RestController
public class SystemController {

    private final SnowProperties props;
    private final Clock clock;

    public SystemController(SnowProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    @GetMapping("/api/v1/system/info")
    public Map<String, Object> info() {
        boolean deepseek = props.ai().deepseekEnabled();
        return Map.of(
                "name", "多业态融合智慧文旅管控系统",
                "now", OffsetDateTime.now(clock),
                "replay", props.demo().replayAt() != null,
                "demoData", props.demo().seed(),
                "aiProvider", deepseek ? "deepseek" : "fake",
                "aiModel", deepseek ? props.ai().model() : "local-rule-simulator",
                "videoProvider", props.video().provider(),
                "mapAttribution", "© OpenStreetMap contributors (ODbL)");
    }
}
