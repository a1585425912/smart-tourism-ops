package com.snowworld.demo;

import com.snowworld.common.SnowProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 启动时按需生成演示数据(snow.demo.seed=true)。每个步骤幂等, 重启不会重复生成。 */
@Component
@Order(10)
public class DemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    private final List<DemoSeedStep> steps;
    private final JdbcClient jdbc;
    private final SnowProperties props;
    private final Clock clock;

    public DemoSeeder(List<DemoSeedStep> steps, JdbcClient jdbc, SnowProperties props, Clock clock) {
        this.steps = steps.stream().sorted(Comparator.comparingInt(DemoSeedStep::order)).toList();
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!props.demo().seed()) {
            return;
        }
        DemoSeedContext ctx = new DemoSeedContext(jdbc, props, clock, new Random(20260218L),
                OffsetDateTime.now(DemoSeedContext.ZONE), LocalDate.parse(props.demo().historyFrom()));
        for (DemoSeedStep step : steps) {
            if (step.alreadySeeded(ctx)) {
                log.info("演示数据 [{}] 已存在, 跳过", step.name());
                continue;
            }
            long t0 = System.currentTimeMillis();
            step.seed(ctx);
            log.info("演示数据 [{}] 生成完成, 用时 {} ms", step.name(), System.currentTimeMillis() - t0);
        }
    }
}
