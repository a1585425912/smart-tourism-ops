package com.snowworld.demo;

import org.springframework.stereotype.Component;

/** 客流历史回灌: 经演示适配器写入正式数据模型, 之后由 {@link DemoFlowFeeder} 持续补齐。 */
@Component
public class FlowDemoSeedStep implements DemoSeedStep {

    private final DemoFlowFeeder feeder;

    public FlowDemoSeedStep(DemoFlowFeeder feeder) {
        this.feeder = feeder;
    }

    @Override
    public int order() {
        return 200;
    }

    @Override
    public String name() {
        return "客流历史(仿真)";
    }

    @Override
    public boolean alreadySeeded(DemoSeedContext ctx) {
        return ctx.jdbc().sql("SELECT EXISTS (SELECT 1 FROM flow_event WHERE data_mode = 'DEMO')").query(Boolean.class).single();
    }

    @Override
    public void seed(DemoSeedContext ctx) {
        feeder.sync(true, ctx.historyFrom());
    }
}
