package com.buyforu.agent.concurrency;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 ExecutionContext 的 ThreadLocal 作用域管理和状态版本原子计数。
 */
class ExecutionContextTest {

    @Test
    void callSetsAndClearsCurrentContext() {
        assertNull(ExecutionContext.current());

        UUID commandId = UUID.randomUUID();
        ExecutionContext context = new ExecutionContext(commandId, "run-1", 1L,
                Instant.now().plusSeconds(60), 0);

        String result = ExecutionContext.call(context, () -> {
            assertNotNull(ExecutionContext.current());
            assertEquals(commandId, ExecutionContext.current().commandId());
            assertEquals("run-1", ExecutionContext.current().runId());
            return "done";
        });

        assertEquals("done", result);
        assertNull(ExecutionContext.current());
    }

    @Test
    void runSetsAndClearsCurrentContext() {
        assertNull(ExecutionContext.current());

        ExecutionContext context = new ExecutionContext(UUID.randomUUID(), "run-2", 5L,
                Instant.now().plusSeconds(30), 10);

        ExecutionContext.run(context, () -> {
            assertEquals("run-2", ExecutionContext.current().runId());
            assertEquals(5L, ExecutionContext.current().epoch());
        });

        assertNull(ExecutionContext.current());
    }

    @Test
    void nestedCallRestoresOuterContext() {
        ExecutionContext outer = new ExecutionContext(UUID.randomUUID(), "outer", 1L,
                Instant.now().plusSeconds(60), 0);
        ExecutionContext inner = new ExecutionContext(UUID.randomUUID(), "inner", 2L,
                Instant.now().plusSeconds(30), 0);

        ExecutionContext.call(outer, () -> {
            assertEquals("outer", ExecutionContext.current().runId());

            ExecutionContext.call(inner, () -> {
                assertEquals("inner", ExecutionContext.current().runId());
                return null;
            });

            assertEquals("outer", ExecutionContext.current().runId());
            return null;
        });

        assertNull(ExecutionContext.current());
    }

    @Test
    void stateSavedIncrementsExpectedVersion() {
        ExecutionContext context = new ExecutionContext(UUID.randomUUID(), "run-1", 1L,
                Instant.now().plusSeconds(60), 5);

        assertEquals(5, context.expectedStateVersion());
        context.stateSaved();
        assertEquals(6, context.expectedStateVersion());
        context.stateSaved();
        assertEquals(7, context.expectedStateVersion());
    }

    @Test
    void contextClearedEvenOnException() {
        ExecutionContext context = new ExecutionContext(UUID.randomUUID(), "run-1", 1L,
                Instant.now().plusSeconds(60), 0);

        assertThrows(RuntimeException.class, () ->
                ExecutionContext.call(context, () -> { throw new RuntimeException("boom"); }));

        assertNull(ExecutionContext.current());
    }

    @Test
    void deadlineAtIsAccessible() {
        Instant deadline = Instant.parse("2026-08-26T12:00:00Z");
        ExecutionContext context = new ExecutionContext(UUID.randomUUID(), "run-1", 1L, deadline, 0);

        assertEquals(deadline, context.deadlineAt());
    }
}
