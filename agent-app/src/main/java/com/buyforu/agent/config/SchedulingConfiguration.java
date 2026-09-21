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

    /**
     * 审计清理单独一条线程：保留窗口拉长到 90 天后，首轮批量删除会明显变长，
     * 不能让它和队列重建共用 maintenanceScheduler 把彼此堵住。
     */
    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskScheduler retentionScheduler() {
        return scheduler("buyforu-retention-", 1);
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
