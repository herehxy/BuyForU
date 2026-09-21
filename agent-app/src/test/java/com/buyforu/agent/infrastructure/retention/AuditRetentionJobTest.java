package com.buyforu.agent.infrastructure.retention;

import com.buyforu.agent.concurrency.RunEventRepository;
import com.buyforu.agent.infrastructure.commerce.ToolCallAudit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 保留策略的窗口、批量上限和指标上报。原先的硬编码 7 天窗口没有留下测试缝，改动后补上。 */
class AuditRetentionJobTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final RunEventRepository events = mock(RunEventRepository.class);
    private final ToolCallAudit toolCalls = mock(ToolCallAudit.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private AuditRetentionJob job(Duration runEvent, Duration toolCall, int batchSize, int maxBatches) {
        return new AuditRetentionJob(events, toolCalls,
                new AuditRetentionProperties(runEvent, toolCall, batchSize, maxBatches), meters, clock);
    }

    @Test
    void appliesTheConfiguredWindowToEachTable() {
        when(events.deleteOlderThan(any(), anyInt())).thenReturn(0);
        when(toolCalls.deleteOlderThan(any(), anyInt())).thenReturn(0);

        job(Duration.ofDays(90), Duration.ofDays(30), 1000, 20).purge();

        // 两张表原先的策略互相矛盾（run_event 7 天、tool_call 永久），这里锁定各自独立可配。
        verify(events).deleteOlderThan(NOW.minus(Duration.ofDays(90)), 1000);
        verify(toolCalls).deleteOlderThan(NOW.minus(Duration.ofDays(30)), 1000);
    }

    @Test
    void stopsOnceABatchIsNotFull() {
        when(events.deleteOlderThan(any(), anyInt())).thenReturn(999);
        when(toolCalls.deleteOlderThan(any(), anyInt())).thenReturn(0);

        job(Duration.ofDays(90), Duration.ofDays(90), 1000, 20).purge();

        // 不足一批说明已经清完，不应继续空转。
        verify(events, times(1)).deleteOlderThan(any(), anyInt());
    }

    @Test
    void drainsFullBatchesUntilThePerCycleCap() {
        when(events.deleteOlderThan(any(), anyInt())).thenReturn(1000);
        when(toolCalls.deleteOlderThan(any(), anyInt())).thenReturn(0);

        job(Duration.ofDays(90), Duration.ofDays(90), 1000, 3).purge();

        // 上限用尽即停：积压交给下一周期，不用一个长事务把审计表锁住。
        verify(events, times(3)).deleteOlderThan(any(), anyInt());
        assertEquals(1.0, meters.counter("buyforu_audit_retention_cycle_capped_total",
                "table", "agent_run_event").count());
        assertEquals(3000.0, meters.counter("buyforu_audit_retention_deleted_total",
                "table", "agent_run_event").count());
    }

    @Test
    void reportsNothingWhenNoRowExpired() {
        when(events.deleteOlderThan(any(), anyInt())).thenReturn(0);
        when(toolCalls.deleteOlderThan(any(), anyInt())).thenReturn(0);

        job(Duration.ofDays(90), Duration.ofDays(90), 1000, 20).purge();

        assertEquals(0.0, meters.counter("buyforu_audit_retention_deleted_total",
                "table", "agent_run_event").count());
        assertEquals(0.0, meters.counter("buyforu_audit_retention_cycle_capped_total",
                "table", "agent_run_event").count());
    }

    @Test
    void rejectsANonPositiveWindowInsteadOfSilentlyRewritingIt() {
        assertThrows(IllegalArgumentException.class,
                () -> new AuditRetentionProperties(Duration.ZERO, Duration.ofDays(30), 1000, 20));
        assertThrows(IllegalArgumentException.class,
                () -> new AuditRetentionProperties(Duration.ofDays(-1), Duration.ofDays(30), 1000, 20));
    }

    @Test
    void defaultsMissingWindowToNinetyDays() {
        AuditRetentionProperties properties = new AuditRetentionProperties(null, null, 0, 0);

        assertEquals(Duration.ofDays(90), properties.runEvent());
        assertEquals(Duration.ofDays(90), properties.toolCall());
        assertEquals(1000, properties.batchSize());
        assertEquals(20, properties.maxBatchesPerCycle());
    }
}
