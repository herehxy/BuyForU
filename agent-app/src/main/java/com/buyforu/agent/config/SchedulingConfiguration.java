package com.buyforu.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 默认 @Scheduled 只有一条线程。dispatch 一堵，心跳就停，租约会误过期。
 * 所以派发、续租、打扫各用自己的线程池。
 */
@Configuration
public class SchedulingConfiguration {
    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskScheduler dispatchScheduler() {
        return scheduler("buyforu-dispatch-", 1);
    }

    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskScheduler leaseScheduler() {
        return scheduler("buyforu-lease-", 2);
    }

    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskScheduler maintenanceScheduler() {
        return scheduler("buyforu-maint-", 1);
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
