package com.buyforu.commerce.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 预占回收和 Outbox 投递分开跑，避免一个慢任务堵住另一个。 */
@Configuration
public class SchedulingConfiguration {
    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskScheduler leaseScheduler() {
        return scheduler("buyforu-commerce-lease-", 1);
    }

    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskScheduler outboxScheduler() {
        return scheduler("buyforu-outbox-", 1);
    }

    private static ThreadPoolTaskScheduler scheduler(String prefix, int poolSize) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(prefix);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }
}
