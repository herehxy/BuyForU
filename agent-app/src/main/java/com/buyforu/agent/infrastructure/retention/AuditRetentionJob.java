package com.buyforu.agent.infrastructure.retention;

import com.buyforu.agent.concurrency.RunEventRepository;
import com.buyforu.agent.infrastructure.commerce.ToolCallAudit;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 审计数据保留任务。
 *
 * <p>改动原因：原先 {@code agent_run_event} 的清理硬编码在 {@code CommandWorker} 里、窗口固定 7 天，
 * 而 {@code tool_call} 完全没有清理任务。两张审计表的保留策略互相矛盾——归因价值最低的那张永久增长，
 * 而过程证据 7 天就被删掉。审计记录是「线上 Badcase 回流评测集」的唯一过程证据，窗口比一个迭代周期
 * 还短，等发现问题时证据已经没了。</p>
 *
 * <p>从派发器里挪出来还有一个原因：清理不能拖住派发。原先它和队列重建共用单线程的
 * {@code maintenanceScheduler}，窗口拉长到 90 天后首轮批量删除会明显变长。现在清理走独立的
 * {@code retentionScheduler}，并且单周期删除量有上限，不追求一个周期清空积压。</p>
 */
@Component
public class AuditRetentionJob {
    private static final Logger log = LoggerFactory.getLogger(AuditRetentionJob.class);

    private final RunEventRepository events;
    private final ToolCallAudit toolCalls;
    private final AuditRetentionProperties properties;
    private final MeterRegistry meters;
    private final Clock clock;

    @Autowired
    public AuditRetentionJob(RunEventRepository events, ToolCallAudit toolCalls,
                             AuditRetentionProperties properties, MeterRegistry meters) {
        this(events, toolCalls, properties, meters, Clock.systemUTC());
    }

    AuditRetentionJob(RunEventRepository events, ToolCallAudit toolCalls,
                      AuditRetentionProperties properties, MeterRegistry meters, Clock clock) {
        this.events = events;
        this.toolCalls = toolCalls;
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${buyforu.retention.poll-delay:PT1H}", scheduler = "retentionScheduler")
    void purge() {
        purge("agent_run_event", properties.runEvent(),
                cutoff -> events.deleteOlderThan(cutoff, properties.batchSize()));
        purge("tool_call", properties.toolCall(),
                cutoff -> toolCalls.deleteOlderThan(cutoff, properties.batchSize()));
    }

    /** 单周期删除量封顶：积压交给后续周期继续清，避免一次长事务把审计表锁住。 */
    private void purge(String table, Duration window, BatchDeleter deleteBatch) {
        Instant cutoff = clock.instant().minus(window);
        int deleted = 0;
        for (int cycle = 0; cycle < properties.maxBatchesPerCycle(); cycle++) {
            int batch = deleteBatch.delete(cutoff);
            deleted += batch;
            if (batch < properties.batchSize()) {
                report(table, deleted, cutoff, false);
                return;
            }
        }
        report(table, deleted, cutoff, true);
    }

    private void report(String table, int deleted, Instant cutoff, boolean capped) {
        if (capped) {
            // 与 OutboxDispatcher 的 cycle_capped 指标同构：上限被打满本身就是要看的信号。
            meters.counter("buyforu_audit_retention_cycle_capped_total", "table", table).increment();
        }
        if (deleted == 0) return;
        meters.counter("buyforu_audit_retention_deleted_total", "table", table).increment(deleted);
        if (capped) {
            log.warn("audit retention hit the per-cycle cap on {}: deleted {} rows older than {}; "
                    + "the rest will be reclaimed next cycle", table, deleted, cutoff);
        } else {
            log.info("audit retention purged {} rows older than {} from {}", deleted, cutoff, table);
        }
    }

    @FunctionalInterface
    private interface BatchDeleter {
        int delete(Instant cutoff);
    }
}
