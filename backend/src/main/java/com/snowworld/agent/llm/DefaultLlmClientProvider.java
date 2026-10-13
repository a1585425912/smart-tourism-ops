package com.snowworld.agent.llm;

import com.snowworld.common.SnowProperties;
import org.springframework.stereotype.Component;

/** snow.ai.provider=deepseek 且配置了密钥时使用 DeepSeek, 否则使用本地规则模拟器(界面会明确标注)。 */
@Component
public class DefaultLlmClientProvider implements LlmClientProvider {

    private final SnowProperties props;
    private final DeepSeekClient deepSeek;
    private final FakeLlmClient fake;

    public DefaultLlmClientProvider(SnowProperties props, DeepSeekClient deepSeek, FakeLlmClient fake) {
        this.props = props;
        this.deepSeek = deepSeek;
        this.fake = fake;
    }

    @Override
    public LlmClient current() {
        return props.ai().deepseekEnabled() ? deepSeek : fake;
    }
}
