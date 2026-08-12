package com.buyforu.commerce.api;

import com.buyforu.commerce.port.CommerceGateway;
import com.buyforu.commerce.port.model.CommerceModels.*;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * CommerceGateway 的 MCP 暴露层。
 * 方法只做结构化协议映射，所有交易规则和权限归属验证仍在 Commerce 应用层完成。
 */
@Component
public final class CommerceMcpTools {
    private final CommerceGateway commerce;

    public CommerceMcpTools(CommerceGateway commerce) {
        this.commerce = commerce;
    }

    @McpTool(name = "commerce_address_list",
            description = "List active serviceable addresses owned by a user",
            generateOutputSchema = true)
    public AddressList listAddresses(
            @McpToolParam(description = "Authenticated user identifier", required = true) String userId) {
        return new AddressList(commerce.listAddresses(userId));
    }

    @McpTool(name = "commerce_catalog_search",
            description = "Search current commerce catalog using explicit shopping constraints",
            generateOutputSchema = true)
    public SearchResult search(@McpToolParam(description = "Validated catalog search request", required = true)
                               SearchRequest request) {
        return commerce.searchProducts(request);
    }

    @McpTool(name = "commerce_quote_calculate",
            description = "Calculate an authoritative current quote without reserving inventory",
            generateOutputSchema = true)
    public Quote quote(@McpToolParam(description = "Quote request", required = true) QuoteRequest request) {
        return commerce.quote(request);
    }

    @McpTool(name = "commerce_address_register",
            description = "Idempotently register a serviceable delivery zone for the authenticated user",
            generateOutputSchema = true)
    public DeliveryAddress registerAddress(
            @McpToolParam(description = "User and delivery zone", required = true) RegisterAddressCommand command,
            @McpToolParam(description = "Mandatory effect and idempotency context", required = true)
            EffectContext effect) {
        return commerce.registerAddress(command, effect);
    }

    @McpTool(name = "commerce_confirmable_order_prepare",
            description = "Atomically calculate quote, reserve inventory and create a confirmable snapshot",
            generateOutputSchema = true)
    public ConfirmableOrderSnapshot prepare(
            @McpToolParam(description = "Order preparation request", required = true) PrepareOrderRequest request,
            @McpToolParam(description = "Mandatory effect and idempotency context", required = true)
            EffectContext effect) {
        return commerce.prepareConfirmableOrder(request, effect);
    }

    @McpTool(name = "commerce_inventory_release",
            description = "Idempotently release an active inventory reservation",
            generateOutputSchema = true)
    public ReleaseResult release(
            @McpToolParam(description = "Reservation identifier", required = true) String reservationId,
            @McpToolParam(description = "Mandatory effect and idempotency context", required = true)
            EffectContext effect) {
        commerce.releaseReservation(reservationId, effect);
        return new ReleaseResult(reservationId, true);
    }

    @McpTool(name = "commerce_order_create",
            description = "Create an order from a valid snapshot and matching human approval proof",
            generateOutputSchema = true)
    public Order createOrder(
            @McpToolParam(description = "Validated order command with approval proof", required = true)
            CreateOrderCommand command,
            @McpToolParam(description = "Mandatory effect and idempotency context", required = true)
            EffectContext effect) {
        return commerce.createOrder(command, effect);
    }

    public record ReleaseResult(String reservationId, boolean released) { }
    public record AddressList(java.util.List<DeliveryAddress> addresses) { }
}
