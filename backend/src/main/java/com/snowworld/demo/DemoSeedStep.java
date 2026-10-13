package com.snowworld.demo;

/**
 * 演示数据的一个生成步骤。各模块各自实现并注册为 Bean, 由 {@link DemoSeeder} 按 order 顺序执行。
 *
 * <p>约定的顺序: 100 基础(景区/区域/用户/设备/数据源), 200 客流历史, 300 巡检, 400 网评,
 * 500 业态/团队/画像, 900 预测训练。每个步骤自行判断是否已生成过(幂等)。
 * 生成的数据一律标记 data_mode = DEMO。
 */
public interface DemoSeedStep {

    int order();

    String name();

    /** 是否已经生成过, 已生成则跳过。 */
    boolean alreadySeeded(DemoSeedContext ctx);

    void seed(DemoSeedContext ctx) throws Exception;
}
