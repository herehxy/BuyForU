package com.buyforu.agent.infrastructure.commerce;

import com.buyforu.commerce.port.CommerceGateway;
import com.buyforu.commerce.port.model.CommerceModels.*;

/**
 * Optional MCP transport adapter. It is deliberately not the domain port and
 * can be exchanged for HTTP or an in-process implementation without changing
 * the workflow or commerce domain.
 */
public final class McpCommerceGatewayAdapter implements CommerceGateway {
    private final McpCommerceToolClient client;

    public McpCommerceGatewayAdapter(McpCommerceToolClient client) {
        this.client = client;
    }

    @Override
    public java.util.List<DeliveryAddress> listAddresses(String userId) {
        return client.addressList(userId);
    }

    @Override
    public java.util.List<InventoryItem> listInventory() {
        return client.inventoryList();
    }

    @Override
    public SearchResult searchProducts(SearchRequest request) {
        return client.catalogSearch(request);
    }

    @Override
    public Quote quote(QuoteRequest request) {
        return client.quoteCalculate(request);
    }

    @Override
    public DeliveryAddress registerAddress(RegisterAddressCommand command, EffectContext effectContext) {
        return client.addressRegister(command, effectContext);
    }

    @Override
    public ConfirmableOrderSnapshot prepareConfirmableOrder(PrepareOrderRequest request, EffectContext effectContext) {
        return client.confirmableOrderPrepare(request, effectContext);
    }

    @Override
    public void releaseReservation(String reservationId, EffectContext effectContext) {
        client.inventoryRelease(reservationId, effectContext);
    }

    @Override
    public Order createOrder(CreateOrderCommand command, EffectContext effectContext) {
        return client.orderCreate(command, effectContext);
    }
}
