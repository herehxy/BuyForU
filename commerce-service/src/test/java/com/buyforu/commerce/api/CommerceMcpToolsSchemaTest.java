package com.buyforu.commerce.api;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.method.tool.utils.McpJsonSchemaGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 锁定 MCP 的真实 JSON Schema，避免可空领域字段在协议层被意外升级为必填字段。 */
@SuppressWarnings("deprecation")
class CommerceMcpToolsSchemaTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void catalogSearchKeepsNullableConstraintsOptional() throws Exception {
        Method method = CommerceMcpTools.class.getMethod("search", CommerceMcpTools.CatalogSearchInput.class);
        Set<String> required = nestedRequired(method, "request");

        assertTrue(required.contains("userId"));
        assertTrue(required.contains("quantity"));
        assertFalse(required.contains("budgetMax"));
        assertFalse(required.contains("budgetMin"));
        assertFalse(required.contains("deliveryBy"));
        assertFalse(required.contains("addressId"));
    }

    @Test
    void confirmableOrderKeepsMissingBudgetValid() throws Exception {
        Method method = CommerceMcpTools.class.getMethod("prepare", CommerceMcpTools.PrepareOrderInput.class,
                com.buyforu.commerce.port.model.CommerceModels.EffectContext.class);
        Set<String> required = nestedRequired(method, "request");

        assertTrue(required.containsAll(Set.of("userId", "skuId", "quantity", "addressId")));
        assertFalse(required.contains("budgetMax"));
    }

    @Test
    void orderLookupKeepsMissingOrderOptional() throws Exception {
        Method method = SchemaProbe.class.getMethod("probe", CommerceMcpTools.OrderLookupResult.class);
        JsonNode root = json.readTree(McpJsonSchemaGenerator.generateForMethodInput(method));
        JsonNode nested = resolve(root, root.path("properties").path("result"));
        Set<String> required = new HashSet<>();
        nested.path("required").forEach(item -> required.add(item.asText()));
        assertTrue(required.contains("found"));
        assertFalse(required.contains("order"));
    }

    public static final class SchemaProbe {
        public void probe(CommerceMcpTools.OrderLookupResult result) {
        }
    }

    private Set<String> nestedRequired(Method method, String property) {
        JsonNode root = json.readTree(McpJsonSchemaGenerator.generateForMethodInput(method));
        JsonNode nested = resolve(root, root.path("properties").path(property));
        Set<String> required = new HashSet<>();
        nested.path("required").forEach(item -> required.add(item.asText()));
        return required;
    }

    /** Victools 可能内联对象，也可能通过 #/$defs/... 引用；测试同时支持两种输出。 */
    private JsonNode resolve(JsonNode root, JsonNode schema) {
        String ref = schema.path("$ref").asText();
        if (ref.isBlank()) return schema;
        JsonNode resolved = root;
        for (String token : ref.substring(2).split("/")) resolved = resolved.path(token);
        return resolved;
    }
}
