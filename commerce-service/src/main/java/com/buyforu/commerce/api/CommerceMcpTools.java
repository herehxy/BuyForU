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

    @McpTool(name = "commerce_inventory_list",
            description = "List current sellable inventory and active reservations",
            generateOutputSchema = true)
    public InventoryList listInventory() {
        return new InventoryList(commerce.listInventory());
    }

    @McpTool(name = "commerce_catalog_search",
            description = "Search current commerce catalog using explicit shopping constraints",
            generateOutputSchema = true)
    public SearchResult search(@McpToolParam(description = "Validated catalog search request", required = true)
                               CatalogSearchInput request) {
        return commerce.searchProducts(request.toDomain());
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
            @McpToolParam(description = "Order preparation request", required = true) PrepareOrderInput request,
            @McpToolParam(description = "Mandatory effect and idempotency context", required = true)
            EffectContext effect) {
        return commerce.prepareConfirmableOrder(request.toDomain(), effect);
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
    public record InventoryList(java.util.List<InventoryItem> items) { }

    /**
     * MCP 入参单独建模：JSON Schema 默认把 record 字段全标成 required。
     * deliveryBy / budgetMax 在领域里本来就可空，缺字段必须能搜。
     */
    public record CatalogSearchInput(
            @McpToolParam(description = "Authenticated shopper", required = true) String userId,
            @McpToolParam(description = "Free-text query", required = false) String query,
            @McpToolParam(description = "Catalog category", required = false) String category,
            @McpToolParam(description = "Payable budget ceiling", required = false) Money budgetMax,
            @McpToolParam(description = "Payable budget floor", required = false) Money budgetMin,
            @McpToolParam(description = "Brands to exclude", required = false) java.util.List<String> excludedBrands,
            @McpToolParam(description = "Required product attributes", required = false)
            java.util.Map<String, String> requiredAttributes,
            @McpToolParam(description = "Delivery address", required = false) String addressId,
            @McpToolParam(description = "Latest acceptable delivery date", required = false)
            java.time.LocalDate deliveryBy,
            @McpToolParam(description = "Purchase quantity", required = true) int quantity,
            @McpToolParam(description = "Max candidates", required = false) Integer limit
    ) {
        SearchRequest toDomain() {
            return new SearchRequest(userId, query, category, budgetMax, budgetMin, excludedBrands, requiredAttributes,
                    addressId, deliveryBy, quantity, limit == null ? 10 : limit);
        }
    }

    /**
     * 确认快照的 MCP 协议 DTO。预算上下限均可选，缺省表示该方向没有硬限制；
     * 不能直接暴露领域 record，否则 Schema 生成器会把该字段当成必须出现在 JSON 中。
     */
    public record PrepareOrderInput(
            @McpToolParam(description = "Authenticated shopper", required = true) String userId,
            @McpToolParam(description = "Selected SKU", required = true) String skuId,
            @McpToolParam(description = "Purchase quantity", required = true) int quantity,
            @McpToolParam(description = "Authenticated delivery address", required = true) String addressId,
            @McpToolParam(description = "Optional payable budget ceiling", required = false) Money budgetMax,
            @McpToolParam(description = "Optional payable budget floor", required = false) Money budgetMin
    ) {
        PrepareOrderRequest toDomain() {
            return new PrepareOrderRequest(userId, skuId, quantity, addressId, budgetMax, budgetMin);
        }
    }
}
