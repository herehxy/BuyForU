package com.buyforu.agent.infrastructure.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 审计数据的保留窗口。
 *
 * <p>默认 90 天而不是 7 天：审计记录是「线上 Badcase 回流评测集」的唯一过程证据，
 * 窗口短于一个迭代周期，等发现问题时证据已经被删掉了。</p>
 */
@ConfigurationProperties("buyforu.retention")
public record AuditRetentionProperties(
        Duration runEvent,
        Duration toolCall,
        int batchSize,
        int maxBatchesPerCycle) {

    private static final Duration DEFAULT_WINDOW = Duration.ofDays(90);
    private static final int DEFAULT_BATCH_SIZE = 1000;
    private static final int DEFAULT_MAX_BATCHES_PER_CYCLE = 20;

    public AuditRetentionProperties {
        runEvent = window(runEvent, "buyforu.retention.run-event");
        toolCall = window(toolCall, "buyforu.retention.tool-call");
        batchSize = batchSize <= 0 ? DEFAULT_BATCH_SIZE : batchSize;
        maxBatchesPerCycle = maxBatchesPerCycle <= 0 ? DEFAULT_MAX_BATCHES_PER_CYCLE : maxBatchesPerCycle;
    }

    /**
     * 未配置时取默认值；显式配置了非法值则直接失败，不静默改写运维意图。
     */
    private static Duration window(Duration configured, String name) {
        if (configured == null) return DEFAULT_WINDOW;
        if (configured.isZero() || configured.isNegative()) {
            throw new IllegalArgumentException(name + " must be a positive duration");
        }
        return configured;
    }
}
