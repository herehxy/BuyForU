package com.buyforu.agent.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.metadata.Usage;

/** 只记录可聚合的模型调用元数据，不记录 Prompt、用户需求或模型原文。 */
final class PlanningCallMetrics {
    private final MeterRegistry meters;

    PlanningCallMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    void retrieval(boolean hit) {
        meters.counter("buyforu_knowledge_retrieval_total", "result", hit ? "hit" : "empty").increment();
    }

    void request(String operation, String result) {
        meters.counter("buyforu_planning_requests_total",
                "operation", operation, "result", result).increment();
    }

    void usage(String operation, Usage usage) {
        if (usage == null) return;
        if (usage.getPromptTokens() != null) {
            meters.counter("buyforu_planning_tokens_total", "type", "prompt")
                    .increment(usage.getPromptTokens());
        }
        if (usage.getCompletionTokens() != null) {
            meters.counter("buyforu_planning_tokens_total", "type", "completion")
                    .increment(usage.getCompletionTokens());
        }
    }
}
