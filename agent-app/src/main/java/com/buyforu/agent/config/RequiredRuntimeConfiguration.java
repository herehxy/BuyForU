package com.buyforu.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 启动即校验真实模型配置；缺少 DeepSeek Key 时明确失败，禁止静默切换为 Demo 模型。 */
@Component
public class RequiredRuntimeConfiguration implements InitializingBean {
    private final String deepSeekApiKey;
    private final String model;

    public RequiredRuntimeConfiguration(@Value("${spring.ai.openai.api-key}") String deepSeekApiKey,
                                        @Value("${spring.ai.openai.chat.options.model}") String model) {
        this.deepSeekApiKey = deepSeekApiKey;
        this.model = model;
    }

    @Override
    public void afterPropertiesSet() {
        if (deepSeekApiKey == null || deepSeekApiKey.isBlank()) {
            throw new IllegalStateException("DEEPSEEK_API_KEY is required; no fallback planning model is configured");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("DEEPSEEK_MODEL is required");
        }
    }
}
