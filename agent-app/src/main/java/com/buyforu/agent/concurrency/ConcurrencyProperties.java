package com.buyforu.agent.concurrency;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 所有容量参数均可通过环境变量调整，代码中不散落不可运维的魔法数字。 */
@ConfigurationProperties("buyforu.concurrency")
public record ConcurrencyProperties(
        String instanceId,
        Duration leaseDuration,
        Duration leaseHeartbeat,
        int planningWorkers,
        int transactionWorkers,
        int controlWorkers,
        int deepseekConcurrency,
        int mcpReadConcurrency,
        int mcpWriteConcurrency,
        int embeddingConcurrency,
        int planningQueueCapacity,
        int transactionQueueCapacity,
        int perUserQueueCapacity,
        int planningUserRatePerMinute,
        int planningUserBurst,
        int transactionUserRatePerMinute,
        int transactionUserBurst,
        int readUserRatePerMinute,
        int readUserBurst,
        int globalWriteRatePerSecond,
        int globalWriteBurst
) { }
