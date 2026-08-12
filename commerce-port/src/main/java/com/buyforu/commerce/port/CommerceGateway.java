package com.buyforu.commerce.port;

import com.buyforu.commerce.port.model.CommerceModels.*;

/**
 * Commerce 的应用端口，也是 Agent 能接触到的全部交易能力。
 *
 * <p>接口刻意不包含 MCP 类型：MCP 只是传输适配器，未来改用 HTTP、RPC 或进程内调用时，
 * Agent 编排和 Commerce 领域模型无需变化。</p>
 */
public interface CommerceGateway {
    java.util.List<DeliveryAddress> listAddresses(String userId);

    SearchResult searchProducts(SearchRequest request);

    Quote quote(QuoteRequest request);

    DeliveryAddress registerAddress(RegisterAddressCommand command, EffectContext effectContext);

    ConfirmableOrderSnapshot prepareConfirmableOrder(
            PrepareOrderRequest request,
            EffectContext effectContext
    );

    void releaseReservation(String reservationId, EffectContext effectContext);

    Order createOrder(CreateOrderCommand command, EffectContext effectContext);
}
