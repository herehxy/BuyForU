package com.buyforu.commerce.it;

import com.buyforu.commerce.application.JdbcCommerceEngine;
import com.buyforu.commerce.port.model.CommerceModels.Money;
import com.buyforu.commerce.port.model.CommerceModels.SearchRequest;
import com.buyforu.commerce.port.model.CommerceModels.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 PostgreSQL 上的中文查询、品牌/规格 SQL 过滤与 query 回退。 */
@Testcontainers(disabledWithoutDocker = true)
class CatalogSearchIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresSupport.postgres();

    private JdbcCommerceEngine engine;

    @BeforeEach
    void setUp() {
        DataSource dataSource = PostgresSupport.dataSource(POSTGRES, "commerce_schema");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("UPDATE commerce_schema.inventory SET available_quantity=8 WHERE sku_id='sku-air-16'");
        jdbc.update("UPDATE commerce_schema.inventory SET available_quantity=5 WHERE sku_id='sku-pro-16'");
        engine = new JdbcCommerceEngine(jdbc, JsonMapper.builder().findAndAddModules().build());
    }

    @Test
    void chineseQueryFiltersBeforeLimitAndKeepsQueryMeta() {
        SearchResult result = search("想买一台轻薄本", List.of(), Map.of());

        assertTrue(result.queryMatched());
        assertFalse(result.candidates().isEmpty());
    }

    @Test
    void unmatchedQueryFallsBackWithoutDroppingHardConstraints() {
        SearchResult result = search("完全不存在的词", List.of("Pine"), Map.of("memory", "16GB"));

        assertFalse(result.queryMatched());
        assertFalse(result.candidates().isEmpty());
        assertTrue(result.candidates().stream().noneMatch(candidate -> "Pine".equals(candidate.brand())));
        assertTrue(result.candidates().stream()
                .allMatch(candidate -> "16GB".equals(candidate.attributes().get("memory"))));
    }

    private SearchResult search(String query, List<String> excludedBrands, Map<String, String> attributes) {
        return engine.searchProducts(new SearchRequest("u-it", query, "laptop", Money.cny("5000"),
                null, excludedBrands, attributes, address(), null, 1, 10));
    }

    private String address() {
        return engine.registerAddress(
                new com.buyforu.commerce.port.model.CommerceModels.RegisterAddressCommand("u-it", "CN-EAST"),
                new com.buyforu.commerce.port.model.CommerceModels.EffectContext(
                        "addr-it", "addr-it", "run-it", "address", 0, "u-it", "trace-it")).addressId();
    }
}
