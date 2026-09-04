package com.buyforu.agent.application;

import com.buyforu.commerce.port.CommerceGateway;
import com.buyforu.commerce.port.model.CommerceModels.DeliveryAddress;
import com.buyforu.commerce.port.model.CommerceModels.EffectContext;
import com.buyforu.commerce.port.model.CommerceModels.RegisterAddressCommand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 验证配送地址服务的幂等键校验和确定性 effectId 生成。
 */
class DeliveryAddressServiceTest {

    private final CommerceGateway commerce = mock(CommerceGateway.class);
    private final DeliveryAddressService service = new DeliveryAddressService(commerce);

    @Test
    void registerRejectsNullIdempotencyKey() {
        assertThrows(IllegalArgumentException.class,
                () -> service.register("user-1", "zone-1", null));
    }

    @Test
    void registerRejectsBlankIdempotencyKey() {
        assertThrows(IllegalArgumentException.class,
                () -> service.register("user-1", "zone-1", "  "));
    }

    @Test
    void registerRejectsEmptyIdempotencyKey() {
        assertThrows(IllegalArgumentException.class,
                () -> service.register("user-1", "zone-1", ""));
    }

    @Test
    void registerCallsCommerceWithDeterministicEffectId() {
        DeliveryAddress expected = new DeliveryAddress("addr-1", "user-1", "zone-1", 3);
        when(commerce.registerAddress(any(RegisterAddressCommand.class), any(EffectContext.class)))
                .thenReturn(expected);

        DeliveryAddress result = service.register("user-1", "zone-1", "key-1");

        assertEquals(expected, result);
        verify(commerce).registerAddress(
                argThat(cmd -> "user-1".equals(cmd.userId()) && "zone-1".equals(cmd.zoneCode())),
                argThat(ctx -> ctx.effectId() != null && !ctx.effectId().isBlank()
                        && ctx.userId().equals("user-1")));
    }

    @Test
    void sameInputsProduceSameEffectId() {
        when(commerce.registerAddress(any(), any())).thenReturn(
                new DeliveryAddress("addr-1", "user-1", "zone-1", 3));

        service.register("user-1", "zone-1", "key-1");
        service.register("user-1", "zone-1", "key-1");

        // 两次调用应传入相同的 effectId
        var captor = org.mockito.ArgumentCaptor.forClass(EffectContext.class);
        verify(commerce, times(2)).registerAddress(any(), captor.capture());

        List<EffectContext> contexts = captor.getAllValues();
        assertEquals(contexts.get(0).effectId(), contexts.get(1).effectId());
    }

    @Test
    void differentUsersProduceDifferentEffectIds() {
        when(commerce.registerAddress(any(), any())).thenReturn(
                new DeliveryAddress("addr-1", "user-1", "zone-1", 3));

        service.register("user-1", "zone-1", "key-1");
        service.register("user-2", "zone-1", "key-1");

        var captor = org.mockito.ArgumentCaptor.forClass(EffectContext.class);
        verify(commerce, times(2)).registerAddress(any(), captor.capture());

        List<EffectContext> contexts = captor.getAllValues();
        assertNotEquals(contexts.get(0).effectId(), contexts.get(1).effectId());
    }

    @Test
    void differentKeysProduceDifferentEffectIds() {
        when(commerce.registerAddress(any(), any())).thenReturn(
                new DeliveryAddress("addr-1", "user-1", "zone-1", 3));

        service.register("user-1", "zone-1", "key-1");
        service.register("user-1", "zone-1", "key-2");

        var captor = org.mockito.ArgumentCaptor.forClass(EffectContext.class);
        verify(commerce, times(2)).registerAddress(any(), captor.capture());

        List<EffectContext> contexts = captor.getAllValues();
        assertNotEquals(contexts.get(0).effectId(), contexts.get(1).effectId());
    }

    @Test
    void listDelegatesToCommerce() {
        List<DeliveryAddress> expected = List.of(
                new DeliveryAddress("addr-1", "user-1", "zone-1", 3));
        when(commerce.listAddresses("user-1")).thenReturn(expected);

        List<DeliveryAddress> result = service.list("user-1");

        assertEquals(expected, result);
        verify(commerce).listAddresses("user-1");
    }
}
