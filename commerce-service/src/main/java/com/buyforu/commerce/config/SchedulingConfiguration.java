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

    /**
     * CLAIMED 回收必须和投递分开跑。
     *
     * <p>投递是"一次跑完整个积压才返回"的循环，Webhook 慢但成功时这一轮可能要跑很久；
     * 共用一个单线程调度器，固定延迟的回收任务只能排在它后面，崩溃实例留下的 CLAIMED 行
     * 迟迟回不到 PENDING，而回收本身又不需要和投递抢任何资源。</p>
     */
    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskScheduler outboxReclaimScheduler() {
        return scheduler("buyforu-outbox-reclaim-", 1);
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
