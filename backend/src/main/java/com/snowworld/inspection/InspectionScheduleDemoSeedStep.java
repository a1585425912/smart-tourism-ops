package com.snowworld.inspection;

import com.snowworld.demo.DemoSeedContext;
import com.snowworld.demo.DemoSeedStep;
import org.springframework.stereotype.Component;

/** 启动时补齐区域早晚班演示计划，独立于已有少量流程样例。 */
@Component
public class InspectionScheduleDemoSeedStep implements DemoSeedStep {
    private final InspectionDemoSeedStep seedStep;

    public InspectionScheduleDemoSeedStep(InspectionDemoSeedStep seedStep) {
        this.seedStep = seedStep;
    }

    @Override
    public int order() { return 310; }

    @Override
    public String name() { return "历史区域早晚班巡检计划"; }

    @Override
    public boolean alreadySeeded(DemoSeedContext ctx) { return false; }

    @Override
    public void seed(DemoSeedContext ctx) { seedStep.seedScheduled(ctx); }
}
