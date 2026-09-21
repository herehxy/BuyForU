package com.buyforu.commerce.application;

import com.buyforu.commerce.port.model.CommerceModels.*;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单取消（第二条写路径）的语义断言，跑在进程内替身上。
 *
 * <p>这里覆盖的是"逻辑栅栏"：幂等键锚点、状态条件更新、预占终态区分、越权拒绝。
 * 真实行锁、并发与库存守恒律由 {@code OrderCancellationIT} 在 Postgres 上验证——
 * 替身是 {@code synchronized} 的，天然串行，证明不了锁顺序。</p>
 *
 * <p><b>替身必须与生产语义逐条对齐</b>：本类同时是"测试替身没同步"这类假绿的守卫。</p>
 */
class OrderCancellationTest {
    private static final int AIR_INITIAL_STOCK = 8;

    private final Clock clock = Clock.fixed(Instant.parse("2026-08-12T08:00:00Z"), ZoneOffset.UTC);

    // ===== 11.1 幂等 ========================================================

    @Test
    void cancelReleasesStockExactlyOnce() {
        InMemoryCommerceEngine engine = InMemoryCommerceEngine.seeded(clock);
        Order order = placeOrder(engine, "u-1", "sku-air-16", 1, "a");

        assertEquals(AIR_INITIAL_STOCK - 1, engine.availableStock("sku-air-16"));
        assertEquals(OrderStatus.PENDING_PAYMENT, order.status());

        Order cancelled = cancel(engine, "u-1", order.orderId(), "cancel-a");

        assertEquals(OrderStatus.CANCELLED, cancelled.status());
        // 回补必须精确 +N，多一分少一分都是数据损坏。
        assertEquals(AIR_INITIAL_STOCK, engine.availableStock("sku-air-16"));
        assertNotEquals(order.version(), cancelled.version(), "取消必须推进订单版本");
    }

    @Test
    void repeatedCancelWithSameEffectReplaysRecordedResult() {
        InMemoryCommerceEngine engine = InMemoryCommerceEngine.seeded(clock);
        Order order = placeOrder(engine, "u-1", "sku-air-16", 1, "b");
        EffectContext effect = effect("cancel-b", "u-1");

        Order first = engine.cancelOrder(new CancelOrderCommand(order.orderId(), "u-1"), effect);
        Order replay = engine.cancelOrder(new CancelOrderCommand(order.orderId(), "u-1"), effect);

        assertEquals(first.orderId(), replay.orderId());
        assertEquals(OrderStatus.CANCELLED, replay.status());
        assertEquals(AIR_INITIAL_STOCK, engine.availableStock("sku-air-16"));
    }

    /**
     * 设计 §5.2 的核心断言：取消的幂等身份锚在 {@code orderId} 上，与 run 无关。
     *
     * <p>用一个<b>全新</b>的 effectId 再取消一次，模拟"订单由另一个 run / 另一个入口发起取消"。
     * 若实现以 runId 为锚，这里会派生出第二个幂等键、绕过 ledger，把库存回补两次。</p>
     */
    @Test
    void cancelWithUnrelatedEffectIdStillReleasesOnlyOnce() {
        InMemoryCommerceEngine engine = InMemoryCommerceEngine.seeded(clock);
        Order order = placeOrder(engine, "u-1", "sku-air-16", 1, "c");

        cancel(engine, "u-1", order.orderId(), "cancel-c-run-1");
        Order second = cancel(engine, "u-1", order.orderId(), "cancel-c-run-2");

        assertEquals(OrderStatus.CANCELLED, second.status());
        assertEquals(AIR_INITIAL_STOCK, engine.availableStock("sku-air-16"),
                "第二个无关幂等键不得再次回补库存");
    }

    @Test
    void cancelOfCancelledOrderReturnsRecordedResultWithoutRelease() {
        InMemoryCommerceEngine engine = InMemoryCommerceEngine.seeded(clock);
        Order order = placeOrder(engine, "u-1", "sku-air-16", 2, "d");

        Order first = cancel(engine, "u-1", order.orderId(), "cancel-d-1");
        int stockAfterFirst = engine.availableStock("sku-air-16");
        Order again = cancel(engine, "u-1", order.orderId(), "cancel-d-2");

        assertEquals(first.orderId(), again.orderId());
        assertEquals(OrderStatus.CANCELLED, again.status());
        assertEquals(stockAfterFirst, engine.availableStock("sku-air-16"));
        // 已经是终态：不抛异常也不能悄悄把版本再推一次。
        assertEquals(first.version(), again.version(), "重复取消不得再次推进版本");
    }

    // ===== 11.2 并发 ========================================================

    @Test
    void concurrentCancelWithDistinctEffectsReleasesExactlyOnce() throws Exception {
        InMemoryCommerceEngine engine = InMemoryCommerceEngine.seeded(clock);
        Order order = placeOrder(engine, "u-1", "sku-air-16", 3, "e");

        int threads = 8;
        var executor = Executors.newFixedThreadPool(threads);
        var barrier = new CyclicBarrier(threads);
        try {
            List<Callable<Order>> attempts = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                String tag = "cancel-e-" + index;
                attempts.add(() -> {
                    barrier.await();
                    return cancelIgnoring(engine, "u-1", order.orderId(), tag);
                });
            }
            List<Order> results = new ArrayList<>();
            for (var future : executor.invokeAll(attempts)) results.add(future.get());

            assertTrue(results.stream().allMatch(result -> result.status() == OrderStatus.CANCELLED),
                    "并发取消最终都必须收敛到 CANCELLED");
            assertEquals(AIR_INITIAL_STOCK, engine.availableStock("sku-air-16"),
                    "8 个并发取消只能回补一次库存");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancelReleasesOnlyItsOwnReservation() {
        InMemoryCommerceEngine engine = InMemoryCommerceEngine.seeded(clock);
        Order first = placeOrder(engine, "u-1", "sku-air-16", 1, "f1");
        Order second = placeOrder(engine, "u-2", "sku-air-16", 1, "f2");
        assertEquals(AIR_INITIAL_STOCK - 2, engine.availableStock("sku-air-16"));

        cancel(engine, "u-1", first.orderId(), "cancel-f1");

        assertEquals(AIR_INITIAL_STOCK - 1, engine.availableStock("sku-air-16"));
        assertEquals(OrderStatus.PENDING_PAYMENT, engine.storedOrder(second.orderId()).status(),
                "取消甲订单不得影响乙订单");
        assertEquals(ReservationStatus.CONSUMED, engine.reservationStatus(second.reservationId()));
    }

    // ===== 11.3 守恒律（替身版） =============================================

    @Test
    void inventoryIsConservedAcrossCreateAndCancel() {
        InMemoryCommerceEngine engine = InMemoryCommerceEngine.seeded(clock);
        assertEquals(AIR_INITIAL_STOCK, conserved(engine, "sku-air-16"));

        Order cancelled = placeOrder(engine, "u-1", "sku-air-16", 2, "g1");
        Order kept = placeOrder(engine, "u-2", "sku-air-16", 3, "g2");
        assertEquals(AIR_INITIAL_STOCK, conserved(engine, "sku-air-16"),
                "下单后守恒：可用 + 在途预占 + 已成交预占 必须等于初始库存");

        cancel(engine, "u-1", cancelled.orderId(), "cancel-g1");
        assertEquals(AIR_INITIAL_STOCK, conserved(engine, "sku-air-16"), "取消后守恒仍必须成立");

        // 再取消一次：守恒最容易被打破的就是重复回补这条路径。
        cancel(engine, "u-1", cancelled.orderId(), "cancel-g1-again");
        assertEquals(AIR_INITIAL_STOCK, conserved(engine, "sku-air-16"));
        assertEquals(OrderStatus.PENDING_PAYMENT, engine.storedOrder(kept.orderId()).status());
    }

    // ===== 11.5 越权 ========================================================

    @Test
    void cancelRejectsOtherUsersOrder() {
        InMemoryCommerceEngine engine = InMemoryCommerceEngine.seeded(clock);
        Order order = placeOrder(engine, "u-1", "sku-air-16", 1, "h");

        CommerceException error = assertThrows(CommerceException.class,
                () -> cancel(engine, "u-2", order.orderId(), "cancel-h"));

        assertEquals("ORDER_USER_MISMATCH", error.code());
        assertEquals(OrderStatus.PENDING_PAYMENT, engine.storedOrder(order.orderId()).status());
        assertEquals(AIR_INITIAL_STOCK - 1, engine.availableStock("sku-air-16"),
                "越权失败不得留下任何副作用");
    }

    @Test
    void cancelRequiresEffectUserToMatchCommandUser() {
        InMemoryCommerceEngine engine = InMemoryCommerceEngine.seeded(clock);
        Order order = placeOrder(engine, "u-1", "sku-air-16", 1, "i");

        CommerceException error = assertThrows(CommerceException.class, () -> engine.cancelOrder(
                new CancelOrderCommand(order.orderId(), "u-1"), effect("cancel-i", "u-2")));

        assertEquals("EFFECT_USER_MISMATCH", error.code());
        assertEquals(AIR_INITIAL_STOCK - 1, engine.availableStock("sku-air-16"));
    }

    @Test
    void cancelRejectsUnknownOrder() {
        InMemoryCommerceEngine engine = InMemoryCommerceEngine.seeded(clock);

        CommerceException error = assertThrows(CommerceException.class,
                () -> cancel(engine, "u-1", "order-does-not-exist", "cancel-unknown"));

        assertEquals("ORDER_NOT_FOUND", error.code());
    }

    // ===== 11.6 状态机 ======================================================

    @Test
    void cancelledReservationIsDistinguishableFromPlainRelease() {
        InMemoryCommerceEngine engine = InMemoryCommerceEngine.seeded(clock);

        // 成交前释放：prepare 出预占但不 create，再走 releaseReservation。
        ConfirmableOrderSnapshot dangling = engine.prepareConfirmableOrder(
                new PrepareOrderRequest("u-1", "sku-air-16", 1, "addr-u-1"), effect("prepare-j1", "u-1"));
        engine.releaseReservation(dangling.reservation().reservationId(),
                new EffectContext("release-j1", "release-j1", "run-j1", "release", 0, "u-1", "trace-j1"));

        Order cancelled = placeOrder(engine, "u-1", "sku-air-16", 1, "j2");
        cancel(engine, "u-1", cancelled.orderId(), "cancel-j2");

        assertEquals(ReservationStatus.RELEASED, engine.reservationStatus(dangling.reservation().reservationId()),
                "成交前释放必须记为 RELEASED");
        assertEquals(ReservationStatus.RELEASED_BY_CANCEL, engine.reservationStatus(cancelled.reservationId()),
                "成交后撤销必须记为 RELEASED_BY_CANCEL，两者对账口径不同");
        assertNotEquals(engine.reservationStatus(dangling.reservation().reservationId()),
                engine.reservationStatus(cancelled.reservationId()));
    }

    @Test
    void cancelledOrderStaysAbsorbingUnderRepeatedAttempts() {
        InMemoryCommerceEngine engine = InMemoryCommerceEngine.seeded(clock);
        Order order = placeOrder(engine, "u-1", "sku-air-16", 1, "k");
        Order first = cancel(engine, "u-1", order.orderId(), "cancel-k-1");

        for (int attempt = 0; attempt < 5; attempt++) {
            Order replay = cancel(engine, "u-1", order.orderId(), "cancel-k-replay-" + attempt);
            assertEquals(OrderStatus.CANCELLED, replay.status());
            assertEquals(first.version(), replay.version(), "终态不得被反复推进");
        }
        assertEquals(AIR_INITIAL_STOCK, engine.availableStock("sku-air-16"));
    }

    // ===== helpers ==========================================================

    /** 守恒律：可用 + ACTIVE 预占 + 未取消订单的 CONSUMED 预占 == 初始库存。 */
    private int conserved(InMemoryCommerceEngine engine, String skuId) {
        return engine.availableStock(skuId)
                + engine.quantityInStatus(skuId, ReservationStatus.ACTIVE)
                + engine.quantityInStatus(skuId, ReservationStatus.CONSUMED);
    }

    private Order placeOrder(InMemoryCommerceEngine engine, String userId, String skuId, int quantity, String tag) {
        ConfirmableOrderSnapshot snapshot = engine.prepareConfirmableOrder(
                new PrepareOrderRequest(userId, skuId, quantity, "addr-" + userId),
                effect("prepare-" + tag, userId));
        ApprovalProof approval = new ApprovalProof("approval-" + tag, snapshot.snapshotId(), snapshot.summaryHash(),
                userId, clock.instant(), snapshot.expiresAt());
        return engine.createOrder(new CreateOrderCommand(userId, snapshot.snapshotId(), approval),
                effect("create-" + tag, userId));
    }

    private Order cancel(InMemoryCommerceEngine engine, String userId, String orderId, String tag) {
        return engine.cancelOrder(new CancelOrderCommand(orderId, userId), effect(tag, userId));
    }

    /** 并发用例里把业务异常也算作"看到的终态"，因为它同样不能产生副作用。 */
    private Order cancelIgnoring(InMemoryCommerceEngine engine, String userId, String orderId, String tag) {
        try {
            return cancel(engine, userId, orderId, tag);
        } catch (CommerceException expected) {
            return engine.storedOrder(orderId);
        }
    }

    private static EffectContext effect(String effectId, String userId) {
        return new EffectContext(effectId, effectId, "run-" + effectId, "cancel-order", 0, userId, "trace-" + effectId);
    }
}
