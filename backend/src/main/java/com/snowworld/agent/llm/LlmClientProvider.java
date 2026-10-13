package com.snowworld.agent.llm;

/** 按配置选择当前使用的模型客户端。测试中可替换为脚本化实现。 */
public interface LlmClientProvider {

    LlmClient current();
}
