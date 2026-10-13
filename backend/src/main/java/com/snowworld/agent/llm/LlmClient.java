package com.snowworld.agent.llm;

/** 大模型调用接口。实现: {@link DeepSeekClient}(云端) 与 {@link FakeLlmClient}(本地规则模拟, 测试与离线演示)。 */
public interface LlmClient {

    /** "deepseek" / "fake"。 */
    String provider();

    String model();

    LlmTypes.ChatResult chat(LlmTypes.ChatRequest request);
}
