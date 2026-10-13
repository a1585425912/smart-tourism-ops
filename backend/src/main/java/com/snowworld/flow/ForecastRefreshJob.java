package com.snowworld.flow;

import com.snowworld.forecast.ForecastService;
import com.snowworld.job.JobContext;
import com.snowworld.job.JobHandler;
import com.snowworld.job.JobMapper;
import org.springframework.stereotype.Component;

/** 重新训练预测模型并保存预测批次与回测记录。可安全重复执行(每次生成新批次)。 */
@Component
public class ForecastRefreshJob implements JobHandler {

    public static final String TYPE = "FORECAST_REFRESH";

    private final ForecastService forecast;

    public ForecastRefreshJob(ForecastService forecast) {
        this.forecast = forecast;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void handle(JobMapper.JobRow job, JobContext context) {
        context.checkpoint();
        forecast.refresh(job.getRefId());
    }
}
