package com.buyforu.commerce.application;

/** 已提交 Outbox 事件的发布端口；本地日志和生产 Webhook 是两个 profile 实现。 */
public interface DomainEventPublisher {
    void publish(DomainEvent event);

    /** 事件信封；eventId 同时是下游消费端应使用的幂等键。 */
    record DomainEvent(String eventId, String aggregateType, String aggregateId,
                       String eventType, String payload) {
    }
}
