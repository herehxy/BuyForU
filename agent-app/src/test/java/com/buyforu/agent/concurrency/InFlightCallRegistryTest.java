package com.buyforu.agent.concurrency;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.FutureTask;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InFlightCallRegistryTest {
    @Test
    void cancelStopsRegisteredFuture() {
        InFlightCallRegistry registry = new InFlightCallRegistry();
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
}
