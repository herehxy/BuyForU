package com.buyforu.agent.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.Usage;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlanningCallMetricsTest {
    @Test
    void recordsRetrievalAndTokenUsageWithoutRawPromptData() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        PlanningCallMetrics metrics = new PlanningCallMetrics(meters);

        metrics.retrieval(true);
        metrics.retrieval(false);
        metrics.request("create", "success");
        metrics.usage("create", usage(120, 30));

        assertEquals(1.0, meters.counter("buyforu_knowledge_retrieval_total", "result", "hit").count());
        assertEquals(1.0, meters.counter("buyforu_knowledge_retrieval_total", "result", "empty").count());
        assertEquals(1.0, meters.counter("buyforu_planning_requests_total",
                "operation", "create", "result", "success").count());
        assertEquals(120.0, meters.counter("buyforu_planning_tokens_total", "type", "prompt").count());
        assertEquals(30.0, meters.counter("buyforu_planning_tokens_total", "type", "completion").count());
    }

    private static Usage usage(int prompt, int completion) {
        return new Usage() {
            @Override
            public Integer getPromptTokens() {
                return prompt;
            }

            @Override
            public Integer getCompletionTokens() {
                return completion;
            }

            @Override
            public Object getNativeUsage() {
                return null;
            }
        };
    }
}
