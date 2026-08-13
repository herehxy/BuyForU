package com.buyforu.agent.infrastructure.commerce;

import com.buyforu.commerce.port.model.CommerceModels.*;

/** Commerce MCP Tool 的协议无关客户端接口，方法与服务端 Tool 一一对应。 */
public interface CommerceToolClient {
    java.util.List<DeliveryAddress> addressList(String userId);

    java.util.List<InventoryItem> inventoryList();

    SearchResult catalogSearch(SearchRequest request);

    Quote quoteCalculate(QuoteRequest request);

    DeliveryAddress addressRegister(RegisterAddressCommand command, EffectContext effect);

    ConfirmableOrderSnapshot confirmableOrderPrepare(PrepareOrderRequest request, EffectContext effect);

    void inventoryRelease(String reservationId, EffectContext effect);

    Order orderCreate(CreateOrderCommand command, EffectContext effect);
}
