package com.buyforu.commerce.application;

import com.buyforu.commerce.port.model.CommerceModels.*;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 Commerce 核心算法：写操作幂等、键冲突检测、并发不超卖和审批证明校验。
 */
class InMemoryCommerceEngineTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-12T08:00:00Z"), ZoneOffset.UTC);

    @Test
    void prepareAndCreateOrderAreIdempotent() {
        InMemoryCommerceEngine engine = InMemoryCommerceEngine.seeded(clock);
        PrepareOrderRequest prepareRequest = new PrepareOrderRequest("u-1", "sku-air-16", 1, "addr-1");
        EffectContext prepareEffect = effect("prepare-1", "prepare-snapshot", "u-1");

        ConfirmableOrderSnapshot first = engine.prepareConfirmableOrder(prepareRequest, prepareEffect);
        ConfirmableOrderSnapshot duplicate = engine.prepareConfirmableOrder(prepareRequest, prepareEffect);

        assertEquals(first.snapshotId(), duplicate.snapshotId());
        assertEquals(7, engine.availableStock("sku-air-16"));

        ApprovalProof approval = new ApprovalProof("approval-1", first.snapshotId(), first.summaryHash(),
                "u-1", clock.instant(), first.expiresAt());
        CreateOrderCommand command = new CreateOrderCommand("u-1", first.snapshotId(), approval);
        EffectContext orderEffect = effect("order-1", "create-order", "u-1");

        Order order = engine.createOrder(command, orderEffect);
        Order repeated = engine.createOrder(command, orderEffect);

        assertEquals(order.orderId(), repeated.orderId());
        assertEquals(OrderStatus.PENDING_PAYMENT, order.status());
        assertEquals(1, engine.orderCount());
    }

    @Test
    void rejectsEffectIdReuseWhenOnlyBudgetChanges() {
        InMemoryCommerceEngine engine = InMemoryCommerceEngine.seeded(clock);
        EffectContext effect = effect("same-effect-budget", "prepare-snapshot", "u-1");
        engine.prepareConfirmableOrder(new PrepareOrderRequest("u-1", "sku-air-16", 1, "addr-1", Money.cny("5000")),
                effect);
        CommerceException error = assertThrows(CommerceException.class, () ->
                engine.prepareConfirmableOrder(new PrepareOrderRequest("u-1", "sku-air-16", 1, "addr-1", Money.cny("4000")),
                        effect));
        assertEquals("EFFECT_CONFLICT", error.code());
    }

    @Test
    void rejectsEffectIdReuseWithDifferentRequest() {
        InMemoryCommerceEngine engine = InMemoryCommerceEngine.seeded(clock);
        EffectContext effect = effect("same-effect", "prepare-snapshot", "u-1");
        engine.prepareConfirmableOrder(new PrepareOrderRequest("u-1", "sku-air-16", 1, "addr-1"), effect);

        CommerceException error = assertThrows(CommerceException.class, () ->
                engine.prepareConfirmableOrder(new PrepareOrderRequest("u-1", "sku-air-16", 2, "addr-1"), effect));
        assertEquals("EFFECT_CONFLICT", error.code());
    }

    @Test
    void idempotencyKeyDeduplicatesAcrossDifferentEffectIds() {
        InMemoryCommerceEngine engine = InMemoryCommerceEngine.seeded(clock);
        PrepareOrderRequest request = new PrepareOrderRequest("u-1", "sku-air-16", 1, "addr-1");

        ConfirmableOrderSnapshot first = engine.prepareConfirmableOrder(request,
                effect("effect-a", "stable-client-key", "prepare-snapshot", "u-1"));
        ConfirmableOrderSnapshot replay = engine.prepareConfirmableOrder(request,
                effect("effect-b", "stable-client-key", "prepare-snapshot", "u-1"));

        assertEquals(first.snapshotId(), replay.snapshotId());
        assertEquals(7, engine.availableStock("sku-air-16"));
    }

    @Test
    void snapshotRejectsWhenPayableExceedsBudget() {
        InMemoryCommerceEngine engine = InMemoryCommerceEngine.seeded(clock);
        CommerceException error = assertThrows(CommerceException.class, () -> engine.prepareConfirmableOrder(
                new PrepareOrderRequest("u-1", "sku-air-16", 1, "addr-1", Money.cny("4000")),
                effect("budget-1", "prepare-snapshot", "u-1")));
        assertEquals("BUDGET_EXCEEDED", error.code());
        assertEquals(8, engine.availableStock("sku-air-16"));
    }

    @Test
    void snapshotRejectsWhenPayableIsBelowBudgetFloor() {
        InMemoryCommerceEngine engine = InMemoryCommerceEngine.seeded(clock);
        CommerceException error = assertThrows(CommerceException.class, () -> engine.prepareConfirmableOrder(
                new PrepareOrderRequest("u-1", "sku-air-16", 1, "addr-1", Money.cny("6000"), Money.cny("5500")),
                effect("budget-min-1", "prepare-snapshot", "u-1")));
        assertEquals("BUDGET_BELOW_MINIMUM", error.code());
        // 预算校验必须发生在扣库存之前。
        assertEquals(8, engine.availableStock("sku-air-16"));
    }

    @Test
    void listInventoryShowsLockAsReservedNotAvailable() {
        InMemoryCommerceEngine engine = InMemoryCommerceEngine.seeded(clock);
        InventoryItem before = engine.listInventory().stream()
                .filter(item -> item.skuId().equals("sku-air-16")).findFirst().orElseThrow();
        assertEquals(8, before.availableQuantity());
        assertEquals(0, before.reservedQuantity());

        engine.prepareConfirmableOrder(new PrepareOrderRequest("u-1", "sku-air-16", 1, "addr-1"),
                effect("fx-stock", "prepare", "u-1"));

        InventoryItem after = engine.listInventory().stream()
                .filter(item -> item.skuId().equals("sku-air-16")).findFirst().orElseThrow();
        assertEquals(7, after.availableQuantity());
        assertEquals(1, after.reservedQuantity());
    }

    @Test
    void searchUsesPayableNotListPrice() {
        InMemoryCommerceEngine engine = new InMemoryCommerceEngine(clock, List.of(
                new InMemoryCommerceEngine.CatalogItem("p", "sku-5100", "Promo laptop", "Brand", "laptop",
                        java.util.Map.of(), new java.math.BigDecimal("5100.00"), 3),
                new InMemoryCommerceEngine.CatalogItem("p2", "sku-4999", "List laptop", "Brand", "laptop",
                        java.util.Map.of(), new java.math.BigDecimal("4999.00"), 3)
        ));
        List<ProductCandidate> underPayable = engine.searchProducts(new SearchRequest("u-1", "", "laptop",
                Money.cny("5000"), null, List.of(), java.util.Map.of(), "addr-1", null, 1, 10)).candidates();
        assertEquals(List.of("sku-4999", "sku-5100"), underPayable.stream().map(ProductCandidate::skuId).toList());

        List<ProductCandidate> tight = engine.searchProducts(new SearchRequest("u-1", "", "laptop",
                Money.cny("4000"), null, List.of(), java.util.Map.of(), "addr-1", null, 1, 10)).candidates();
        assertTrue(tight.isEmpty());
    }

    @Test
    void concurrentReservationsNeverOversell() throws Exception {
        InMemoryCommerceEngine engine = new InMemoryCommerceEngine(clock, List.of(
                new InMemoryCommerceEngine.CatalogItem("p", "last-sku", "Last item", "Brand", "test",
                        java.util.Map.of(), new java.math.BigDecimal("10.00"), 1)
        ));
        // 20 个请求并发争抢最后一件库存，最终只能有一个预占成功。
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> attempts = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                int attempt = index;
                attempts.add(() -> {
                    try {
                        engine.prepareConfirmableOrder(
                                new PrepareOrderRequest("u-" + attempt, "last-sku", 1, "addr"),
                                effect("effect-" + attempt, "prepare-snapshot", "u-" + attempt));
                        return true;
                    } catch (CommerceException expected) {
                        return false;
                    }
                });
            }
            long successes = executor.invokeAll(attempts).stream().filter(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }).count();
            assertEquals(1, successes);
            assertEquals(0, engine.availableStock("last-sku"));
        } finally {
            executor.shutdownNow();
        }
    }

    private EffectContext effect(String effectId, String node, String userId) {
        return effect(effectId, effectId, node, userId);
    }

    private EffectContext effect(String effectId, String idempotencyKey, String node, String userId) {
        return new EffectContext(effectId, idempotencyKey, "run-1", node, 0, userId, "trace-1");
    }
}
