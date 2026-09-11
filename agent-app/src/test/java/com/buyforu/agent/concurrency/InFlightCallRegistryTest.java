package com.buyforu.agent.concurrency;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.FutureTask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InFlightCallRegistryTest {
    @Test
    void cancelStopsRegisteredFuture() {
        InFlightCallRegistry registry = new InFlightCallRegistry(new SimpleMeterRegistry());
        UUID commandId = UUID.randomUUID();
        FutureTask<Void> future = new FutureTask<>(() -> {
            Thread.sleep(10_000);
            return null;
        });
        registry.register(commandId, future);

        assertTrue(registry.cancel(commandId));
        assertTrue(future.isCancelled());
        assertFalse(registry.cancel(UUID.randomUUID()));
    }

    /**
     * 在册数量必须能被观测到：没有 TTL、没有兜底清理的前提下，
     * 这个指标只增不减就是"某条路径漏了 clear"的唯一信号。
     */
    @Test
    void publishesInFlightSizeAsGauge() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        InFlightCallRegistry registry = new InFlightCallRegistry(meters);
        assertEquals(0.0, meters.get("buyforu_inflight_calls").gauge().value());

        UUID commandId = UUID.randomUUID();
        FutureTask<Void> future = new FutureTask<>(() -> null);
        registry.register(commandId, future);
        assertEquals(1.0, meters.get("buyforu_inflight_calls").gauge().value());

        registry.clear(commandId, future);
        assertEquals(0.0, meters.get("buyforu_inflight_calls").gauge().value(),
                "已结束的调用不能继续计入，否则指标无法反映泄漏");
    }
}
