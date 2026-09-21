package com.buyforu.agent.application;

import com.buyforu.commerce.port.CommerceGateway;
import com.buyforu.commerce.port.model.CommerceModels.CancelOrderCommand;
import com.buyforu.commerce.port.model.CommerceModels.EffectContext;
import com.buyforu.commerce.port.model.CommerceModels.Money;
import com.buyforu.commerce.port.model.CommerceModels.Order;
import com.buyforu.commerce.port.model.CommerceModels.OrderStatus;
import com.buyforu.commerce.port.model.CommerceModels.Quote;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 锁定订单取消的幂等键派生规则。
 *
 * <p>这是设计 §5.2 的回归锁：取消的 effectId 必须只由 (userId, orderId) 决定，<b>绝不能混入 runId</b>。
 * 一旦有人以"统一派生规则"为名把它改回 runId 派生，同一订单经两个 Run 取消就会拿到两个幂等键，
 * 账本挡不住，库存被回补两次——这里是唯一能提前发现该改动的地方。</p>
 */
class OrderCancellationServiceTest {

    private final CommerceGateway commerce = mock(CommerceGateway.class);
    private final OrderCancellationService service = new OrderCancellationService(commerce);

    @Test
    void cancelRejectsBlankOrderId() {
        assertThrows(IllegalArgumentException.class, () -> service.cancel("user-1", null));
        assertThrows(IllegalArgumentException.class, () -> service.cancel("user-1", "  "));
    }

    @Test
    void cancelSendsOrderAndOwnerToCommerce() {
        when(commerce.cancelOrder(any(), any())).thenReturn(cancelledOrder());

        service.cancel("user-1", "order-1");

        verify(commerce).cancelOrder(
                org.mockito.ArgumentMatchers.argThat(command ->
                        "user-1".equals(command.userId()) && "order-1".equals(command.orderId())),
                org.mockito.ArgumentMatchers.argThat(effect -> "user-1".equals(effect.userId())));
    }

    /**
     * 派生规则被字面量锁死：如果实现开始依赖 runId、随机数或客户端键，这个断言必然变红。
     */
    @Test
    void effectIdIsPureFunctionOfOwnerAndOrder() {
        assertEquals("b709d264d70a6440ceeebf0620b296f0644149ad69fa712437f2dfe121641bfd",
                OrderCancellationService.effectId("user-1", "order-1"),
                "派生规则变了：线上已有的 effect_record 会全部对不上，重放退化为重跑");
    }

    /**
     * 同一个订单、同一属主，从任何"上下文"（不同 Run、不同进程、不同时刻）发起取消都必须得到
     * 同一个 effectId。服务根本不接收 runId，正是为了让这件事在类型层面无法被写错。
     */
    @Test
    void sameOwnerAndOrderAlwaysProduceSameEffectId() {
        when(commerce.cancelOrder(any(), any())).thenReturn(cancelledOrder());

        service.cancel("user-1", "order-1");
        new OrderCancellationService(mock(CommerceGateway.class)).cancel("user-1", "order-1");
        service.cancel("user-1", "order-1");

        ArgumentCaptor<EffectContext> captor = ArgumentCaptor.forClass(EffectContext.class);
        verify(commerce, times(2)).cancelOrder(any(), captor.capture());
        List<EffectContext> contexts = captor.getAllValues();
        assertEquals(contexts.get(0).effectId(), contexts.get(1).effectId());
        assertEquals(OrderCancellationService.effectId("user-1", "order-1"), contexts.get(0).effectId());
        assertTrue(contexts.stream().allMatch(context -> context.idempotencyKey().equals(context.effectId())));
    }

    @Test
    void differentOrdersAndOwnersProduceDifferentEffectIds() {
        assertNotEquals(OrderCancellationService.effectId("user-1", "order-1"),
                OrderCancellationService.effectId("user-1", "order-2"));
        assertNotEquals(OrderCancellationService.effectId("user-1", "order-1"),
                OrderCancellationService.effectId("user-2", "order-1"));
    }

    private static Order cancelledOrder() {
        Instant now = Instant.parse("2026-08-12T08:00:00Z");
        Quote quote = new Quote("quote-1", 1L, "sku-air-16", 1, Money.cny("4999"),
                List.of(), Money.cny("0"), Money.cny("4999"),
                now.plusSeconds(600).atZone(java.time.ZoneOffset.UTC).toLocalDate(), now, now.plusSeconds(300));
        return new Order("order-1", "user-1", "snapshot-1", "reservation-1", quote,
                OrderStatus.CANCELLED, now, 2L);
    }
}
