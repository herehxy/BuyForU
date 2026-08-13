package com.buyforu.agent.infrastructure.commerce;

import com.buyforu.commerce.port.model.CommerceModels.*;
import com.buyforu.commerce.port.CommerceOperationException;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.buyforu.agent.concurrency.DependencyExecutor;
import java.time.Duration;

/**
 * MCP SDK 的真实客户端实现：把领域对象编码为 Tool 参数，解析结构化结果，并记录调用摘要。
 * 原始请求/响应不会写入审计表，避免商品需求或交易数据被无边界复制。
 */
@Component
public final class SpringMcpCommerceToolClient implements McpCommerceToolClient {
    private static final Pattern COMMERCE_CODE = Pattern.compile("\\[([A-Z][A-Z0-9_]+)]");
    private final McpSyncClient client;
    private final ObjectMapper json;
    private final ToolCallAudit audit;
    private final DependencyExecutor dependencies;

    public SpringMcpCommerceToolClient(List<McpSyncClient> clients, ObjectMapper json, ToolCallAudit audit,
                                      DependencyExecutor dependencies) {
        if (clients.size() != 1) {
            throw new IllegalStateException("exactly one Commerce MCP connection is required, found " + clients.size());
        }
        this.client = clients.getFirst();
        this.json = json;
        this.audit = audit;
        this.dependencies = dependencies;
    }

    @Override
    public List<DeliveryAddress> addressList(String userId) {
        return call("commerce_address_list", Map.of("userId", userId), AddressList.class).addresses();
    }

    @Override
    public List<InventoryItem> inventoryList() {
        return call("commerce_inventory_list", Map.of(), InventoryList.class).items();
    }

    @Override
    public SearchResult catalogSearch(SearchRequest request) {
        return call("commerce_catalog_search", Map.of("request", arguments(request)), SearchResult.class);
    }

    @Override
    public Quote quoteCalculate(QuoteRequest request) {
        return call("commerce_quote_calculate", Map.of("request", arguments(request)), Quote.class);
    }

    @Override
    public DeliveryAddress addressRegister(RegisterAddressCommand command, EffectContext effect) {
        return call("commerce_address_register", Map.of(
                "command", arguments(command), "effect", arguments(effect)), DeliveryAddress.class);
    }

    @Override
    public ConfirmableOrderSnapshot confirmableOrderPrepare(PrepareOrderRequest request, EffectContext effect) {
        return call("commerce_confirmable_order_prepare", Map.of(
                "request", arguments(request), "effect", arguments(effect)), ConfirmableOrderSnapshot.class);
    }

    @Override
    public void inventoryRelease(String reservationId, EffectContext effect) {
        call("commerce_inventory_release", Map.of(
                "reservationId", reservationId, "effect", arguments(effect)), ReleaseResult.class);
    }

    @Override
    public Order orderCreate(CreateOrderCommand command, EffectContext effect) {
        return call("commerce_order_create", Map.of(
                "command", arguments(command), "effect", arguments(effect)), Order.class);
    }

    private <T> T call(String toolName, Map<String, Object> arguments, Class<T> resultType) {
        // read tool 没有 EffectContext，仍使用独立的审计 run/trace 标识；写 tool 使用图中的真实标识。
        Map<String, Object> effect = nestedMap(arguments.get("effect"));
        String auditId = audit.started(toolName, string(effect, "runId", "read:" + toolName),
                string(effect, "traceId", "read:" + toolName), string(effect, "effectId", null),
                json.writeValueAsString(arguments));
        try {
            boolean write = effect != null && !effect.isEmpty();
            CallToolResult result = dependencies.call(write ? DependencyExecutor.Dependency.MCP_WRITE
                            : DependencyExecutor.Dependency.MCP_READ, Duration.ofSeconds(write ? 5 : 3), 2,
                    () -> client.callTool(CallToolRequest.builder().name(toolName).arguments(arguments).build()));
            if (Boolean.TRUE.equals(result.isError())) {
                String error = String.valueOf(result.content());
                Matcher code = COMMERCE_CODE.matcher(error);
                if (code.find()) throw new CommerceOperationException(code.group(1), error);
                throw new IllegalStateException("Commerce MCP transport/tool failure: " + toolName + " " + error);
            }
            if (result.structuredContent() == null) {
                throw new IllegalStateException("Commerce MCP tool returned no structured content: " + toolName);
            }
            audit.completed(auditId, json.writeValueAsString(result.structuredContent()));
            return json.convertValue(result.structuredContent(), resultType);
        } catch (RuntimeException failure) {
            audit.failed(auditId, failure);
            throw failure;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nestedMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String string(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        return value == null ? fallback : value.toString();
    }

    private Map<String, Object> arguments(Object value) {
        return new LinkedHashMap<>(json.convertValue(value, new TypeReference<Map<String, Object>>() { }));
    }

    private record ReleaseResult(String reservationId, boolean released) { }
    private record AddressList(List<DeliveryAddress> addresses) { }
    private record InventoryList(List<InventoryItem> items) { }
}
