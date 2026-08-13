package com.buyforu.commerce.it;

import com.buyforu.commerce.application.CommerceException;
import com.buyforu.commerce.application.JdbcCommerceEngine;
import com.buyforu.commerce.port.model.CommerceModels.EffectContext;
import com.buyforu.commerce.port.model.CommerceModels.Money;
import com.buyforu.commerce.port.model.CommerceModels.PrepareOrderRequest;
import com.buyforu.commerce.port.model.CommerceModels.ProductCandidate;
import com.buyforu.commerce.port.model.CommerceModels.SearchRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用真实 Postgres 验证：应付超预算不扣库存；搜索按应付而不是吊牌价过滤。 */
@Testcontainers(disabledWithoutDocker = true)
class BudgetSnapshotIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresSupport.postgres();

    private JdbcCommerceEngine engine;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DataSource dataSource = PostgresSupport.dataSource(POSTGRES, "commerce_schema");
        jdbc = new JdbcTemplate(dataSource);
        engine = new JdbcCommerceEngine(jdbc, JsonMapper.builder().findAndAddModules().build());
        jdbc.update("UPDATE commerce_schema.inventory SET available_quantity=8 WHERE sku_id='sku-air-16'");
        jdbc.update("UPDATE commerce_schema.inventory SET available_quantity=3 WHERE sku_id='sku-max-32'");
    }

    @Test
    void snapshotDoesNotReserveWhenPayableExceedsBudget() {
        CommerceException error = assertThrows(CommerceException.class, () -> engine.prepareConfirmableOrder(
                new PrepareOrderRequest("u-1", "sku-air-16", 1, address("u-1"), Money.cny("4000")),
                effect("budget-it", "u-1")));
        assertEquals("BUDGET_EXCEEDED", error.code());
        assertEquals(8, stock("sku-air-16"));
    }

    @Test
    void searchKeepsSkuWhenOnlyPayableFitsBudget() {
        // sku-max-32 吊牌 6299，满减后 6099。预算 6200 时旧逻辑会丢掉它。
        List<String> skuIds = engine.searchProducts(new SearchRequest("u-1", "", "laptop",
                        Money.cny("6200"), List.of(), java.util.Map.of(), address("u-1"), null, 1, 10))
                .candidates().stream().map(ProductCandidate::skuId).toList();
        assertTrue(skuIds.contains("sku-max-32"));
    }

    private String address(String userId) {
        return engine.registerAddress(
                new com.buyforu.commerce.port.model.CommerceModels.RegisterAddressCommand(userId, "CN-EAST"),
                effect("addr-" + userId, userId)).addressId();
    }

    private int stock(String skuId) {
        Integer value = jdbc.queryForObject(
                "SELECT available_quantity FROM commerce_schema.inventory WHERE sku_id=?", Integer.class, skuId);
        return value == null ? -1 : value;
    }

    private static EffectContext effect(String id, String userId) {
        return new EffectContext(id, id, "run-it", "prepare", 0, userId, "trace-it");
    }
}
