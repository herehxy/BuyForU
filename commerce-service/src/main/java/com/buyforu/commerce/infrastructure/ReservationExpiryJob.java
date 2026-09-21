package com.buyforu.commerce.infrastructure;

import com.buyforu.commerce.application.JdbcCommerceEngine;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定期在事务中释放过期库存预占，保证库存恢复不依赖下一次用户请求。 */
@Component
public class ReservationExpiryJob {
    private final JdbcCommerceEngine commerce;

    public ReservationExpiryJob(JdbcCommerceEngine commerce) {
        this.commerce = commerce;
    }

    @Scheduled(fixedDelayString = "${buyforu.reservations.expiry-poll-delay:PT5S}", scheduler = "leaseScheduler")
    public void releaseExpiredInventory() {
        commerce.expireReservationsNow();
    }
}
