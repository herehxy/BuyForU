package com.buyforu.commerce.infrastructure;

import com.buyforu.commerce.application.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 本地开发事件出口：只记录已提交 Outbox 事件，不假装调用任何外部系统。 */
@Component
@Profile("!production")
public class LocalDomainEventPublisher implements DomainEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(LocalDomainEventPublisher.class);

    @Override
    public void publish(DomainEvent event) {
        log.info("local_commerce_event eventId={} aggregateType={} aggregateId={} eventType={}",
                event.eventId(), event.aggregateType(), event.aggregateId(), event.eventType());
    }
}
