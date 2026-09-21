package com.buyforu.commerce.it;

import com.buyforu.commerce.application.CommerceException;
import com.buyforu.commerce.application.JdbcCommerceEngine;
import com.buyforu.commerce.port.model.CommerceModels.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单取消（第二条写路径）在真实 Postgres 上的断言。
 *
 * <p>替身测试（{@code OrderCancellationTest}）只能证明"逻辑栅栏"，证明不了三件事，
 * 本类专门针对这三件事：</p>
 *
 * <ol>
 *   <li><b>真实行锁与并发</b>：替身方法是 {@code synchronized} 的，天然串行。这里两个线程
 *       各自持真实事务争抢同一行，才谈得上"并发只回补一次"。</li>
 *   <li><b>库存守恒律</b>：把可用、在途预占、已成交预占加总与初始库存对齐。任何重复扣减、
 *       重复回补、漏回补都会破坏它，而单点断言可能漏掉组合场景。</li>
 *   <li><b>原子回滚</b>：取消失败时，订单状态、预占状态、effect ledger 与 Outbox 必须一起消失。</li>
 * </ol>
 *
 * <p><b>本类必须把引擎调用包在真实事务里</b>：{@code JdbcCommerceEngine} 由测试手工构造，
 * {@code @Transactional} 没有代理、不生效。若让语句跑在自动提交下，
 * {@code pg_advisory_xact_lock} 会在语句结束瞬间释放、{@code FOR UPDATE} 形同不存在——
 * 并发用例会给出"看起来通过"的假绿。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class OrderCancellationIT {
    private static final int AIR_INITIAL_STOCK = 8;
    private static final String SKU = "sku-air-16";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresSupport.postgres();

    private static DataSource dataSource;

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private JdbcCommerceEngine engine;
    private tools.jackson.databind.ObjectMapper json;

    @BeforeEach
    void setUp() {
        if (dataSource == null) dataSource = PostgresSupport.dataSource(POSTGRES, "commerce_schema");
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        json = JsonMapper.builder().findAndAddModules().build();
        engine = new JdbcCommerceEngine(jdbc, json);

        // 事务表全清，保证每个用例从同一个空起点出发；目录/库存是种子数据，只把数量复位。
        jdbc.execute("""
                TRUNCATE commerce_schema.orders, commerce_schema.confirmable_snapshot,
                         commerce_schema.inventory_reservation, commerce_schema.effect_record,
                         commerce_schema.outbox_event
                """);
        jdbc.update("UPDATE commerce_schema.inventory SET available_quantity = ? WHERE sku_id = ?",
                AIR_INITIAL_STOCK, SKU);
        jdbc.update("UPDATE commerce_schema.inventory SET available_quantity = 5 WHERE sku_id = 'sku-pro-16'");
    }

    // ===== 11.1 幂等 ========================================================

    @Test
    void cancelReleasesStockExactlyOnce() {
        String userId = "u-cancel-1";
        Order order = placeOrder(userId, SKU, 1, "c1");

        assertEquals(AIR_INITIAL_STOCK - 1, stock(SKU));
        assertEquals(OrderStatus.PENDING_PAYMENT, order.status());

        Order cancelled = cancel(userId, order.orderId(), "c1-cancel");

        assertEquals(OrderStatus.CANCELLED, cancelled.status());
        assertEquals(AIR_INITIAL_STOCK, stock(SKU), "取消必须精确回补 N 件");
        assertEquals("CANCELLED", statusColumnOf(order.orderId()));
        assertNotNull(cancelledAt(order.orderId()), "取消时间必须落库，供审计与对账");
    }

    @Test
    void repeatedCancelWithSameEffectReplaysRecordedResult() {
        String userId = "u-cancel-2";
        Order order = placeOrder(userId, SKU, 1, "c2");
        EffectContext effect = effect("c2-cancel", userId);

        Order first = inTransaction(() -> engine.cancelOrder(new CancelOrderCommand(order.orderId(), userId), effect));
        Order replay = inTransaction(() -> engine.cancelOrder(new CancelOrderCommand(order.orderId(), userId), effect));

        assertEquals(first.orderId(), replay.orderId());
        assertEquals(first.version(), replay.version(), "同一幂等键重放必须返回记录结果而不是重跑");
        assertEquals(AIR_INITIAL_STOCK, stock(SKU));
    }

    /**
     * 设计 §5.2 的核心断言：取消的幂等身份锚在 {@code orderId} 上。
     *
     * <p>用一个全新的幂等键再取消一次，模拟"同一个订单由另一个入口/另一个 Run 发起取消"。
     * 这是引入静默数据损坏的路径：若幂等键以 runId 为锚，两次取消会派生两个键，
     * ledger 挡不住，库存被回补两次，而**任何单点断言都不会变红**。</p>
     */
    @Test
    void cancelWithUnrelatedEffectIdDoesNotReleaseAgain() {
        String userId = "u-cancel-3";
        Order order = placeOrder(userId, SKU, 2, "c3");

        cancel(userId, order.orderId(), "c3-cancel-run-1");
        Order second = cancel(userId, order.orderId(), "c3-cancel-run-2");

        assertEquals(OrderStatus.CANCELLED, second.status());
        assertEquals(AIR_INITIAL_STOCK, stock(SKU), "第二个无关幂等键不得再次回补库存");
        assertEquals(1, count("""
                SELECT count(*) FROM commerce_schema.outbox_event
                WHERE event_type = 'ORDER_CANCELLED' AND aggregate_id = ?
                """, order.orderId()), "取消事件只能有一条");
    }

    @Test
    void cancelOfCancelledOrderReturnsRecordedResult() {
        String userId = "u-cancel-4";
        Order order = placeOrder(userId, SKU, 1, "c4");

        Order first = cancel(userId, order.orderId(), "c4-cancel-1");
        Order again = cancel(userId, order.orderId(), "c4-cancel-2");

        assertEquals(first.orderId(), again.orderId());
        assertEquals(OrderStatus.CANCELLED, again.status());
        assertEquals(first.version(), again.version(), "终态不得被反复推进");
        assertEquals(AIR_INITIAL_STOCK, stock(SKU));
    }

    // ===== 11.2 并发（真实行锁） =============================================

    @Test
    void concurrentCancelWithDistinctEffectsReleasesExactlyOnce() throws Exception {
        String userId = "u-cancel-race";
        Order order = placeOrder(userId, SKU, 3, "race");
        assertEquals(AIR_INITIAL_STOCK - 3, stock(SKU));

        List<Order> results = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        // 两个线程各自一个幂等键 → 不共享 advisory lock，只能靠 orders 行锁 + 状态条件更新来串行化。
        race(List.of(
                () -> results.add(cancel(userId, order.orderId(), "race-cancel-0")),
                () -> results.add(cancel(userId, order.orderId(), "race-cancel-1"))
        ), failures);

        assertTrue(failures.isEmpty(), () -> "并发取消不应抛异常：" + failures);
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(result -> result.status() == OrderStatus.CANCELLED),
                "两个并发取消最终都必须看到 CANCELLED");
        assertEquals(AIR_INITIAL_STOCK, stock(SKU), "两个不同幂等键并发取消，库存只能回补一次");
        assertEquals(1, count("""
                SELECT count(*) FROM commerce_schema.outbox_event
                WHERE event_type = 'ORDER_CANCELLED' AND aggregate_id = ?
                """, order.orderId()));
    }

    // ===== 11.3 守恒律（最强证据） ===========================================

    @Test
    void inventoryConservationInvariantHoldsAcrossCreateAndCancel() {
        String userId = "u-conserve";
        assertEquals(AIR_INITIAL_STOCK, conserved(SKU), "起点必须守恒");

        Order cancelled = placeOrder(userId, SKU, 2, "k1");
        Order kept = placeOrder(userId, SKU, 3, "k2");
        assertEquals(AIR_INITIAL_STOCK, conserved(SKU), "下单后守恒：可用 + 在途 + 已成交 必须等于初始库存");

        cancel(userId, cancelled.orderId(), "k1-cancel");
        assertEquals(AIR_INITIAL_STOCK, conserved(SKU), "取消后守恒仍必须成立");

        // 重复取消是守恒最容易破的路径：每多回补一次都会凭空多出库存。
        cancel(userId, cancelled.orderId(), "k1-cancel-again");
        cancel(userId, cancelled.orderId(), "k1-cancel-third");
        assertEquals(AIR_INITIAL_STOCK, conserved(SKU), "重复取消不得凭空造出库存");
        assertEquals("PENDING_PAYMENT", statusColumnOf(kept.orderId()));
    }

    @Test
    void cancelReleasesOnlyItsOwnReservation() {
        String first = "u-own-1";
        String second = "u-own-2";
        Order mine = placeOrder(first, SKU, 1, "own1");
        Order theirs = placeOrder(second, SKU, 1, "own2");
        assertEquals(AIR_INITIAL_STOCK - 2, stock(SKU));

        cancel(first, mine.orderId(), "own1-cancel");

        assertEquals(AIR_INITIAL_STOCK - 1, stock(SKU));
        assertEquals("PENDING_PAYMENT", statusColumnOf(theirs.orderId()));
        assertEquals("CONSUMED", reservationStatus(theirs.reservationId()), "乙订单的预占不得被牵连");
    }

    // ===== 11.4 锁与死锁 ====================================================

    /**
     * 锁顺序是约定而非编译器保证，任何一次重构都可能破坏它，且只在生产高并发下暴露。
     *
     * <p>这里反复制造 {@code create × cancel} 的交叉：两条路径都要拿 orders 与
     * inventory_reservation 的行锁，顺序一旦反转就会以 40P01（deadlock detected）冒出来。</p>
     */
    @Test
    void concurrentCreateAndCancelDoNotDeadlock() throws Exception {
        String userId = "u-deadlock";
        Order victim = placeOrder(userId, SKU, 1, "dl-victim");

        for (int round = 0; round < 5; round++) {
            String tag = "dl-" + round;
            ConfirmableOrderSnapshot candidate = prepare(userId, SKU, 1, tag);
            List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
            race(List.of(
                    () -> createOrder(userId, candidate, tag + "-create"),
                    () -> cancel(userId, victim.orderId(), tag + "-cancel")
            ), failures);
            assertTrue(failures.isEmpty(),
                    () -> "第 " + tag + " 轮 create × cancel 交叉失败（死锁会以 40P01 出现）：" + failures);
        }

        assertEquals(AIR_INITIAL_STOCK, conserved(SKU), "交叉并发后库存仍必须守恒");
    }

    // ===== 11.5 越权 ========================================================

    @Test
    void cancelRejectsOtherUsersOrder() {
        String owner = "u-sec-owner";
        Order order = placeOrder(owner, SKU, 1, "s1");

        CommerceException error = assertThrows(CommerceException.class,
                () -> cancel("u-sec-intruder", order.orderId(), "s1-intruder"));

        assertEquals("ORDER_USER_MISMATCH", error.code());
        assertEquals("PENDING_PAYMENT", statusColumnOf(order.orderId()));
        assertEquals(AIR_INITIAL_STOCK - 1, stock(SKU), "越权失败不得留下任何副作用");
    }

    @Test
    void cancelRequiresEffectUserToMatchCommandUser() {
        String userId = "u-sec-effect";
        Order order = placeOrder(userId, SKU, 1, "s2");

        CommerceException error = assertThrows(CommerceException.class, () -> inTransaction(() ->
                engine.cancelOrder(new CancelOrderCommand(order.orderId(), userId), effect("s2-bad", "another-user"))));

        assertEquals("EFFECT_USER_MISMATCH", error.code());
        assertEquals("PENDING_PAYMENT", statusColumnOf(order.orderId()));
        assertEquals(AIR_INITIAL_STOCK - 1, stock(SKU));
    }

    @Test
    void cancelRejectsUnknownOrder() {
        CommerceException error = assertThrows(CommerceException.class,
                () -> cancel("u-sec-unknown", "order-does-not-exist", "s3"));

        assertEquals("ORDER_NOT_FOUND", error.code());
    }

    // ===== 原子回滚（本节是"一个事务"这句话的唯一证据） ======================

    /**
     * 取消失败时，订单状态、effect ledger、Outbox 必须**一起**消失。
     *
     * <p>为了让失败点落在"订单状态已更新之后"，这里把预占伪造成 {@code RELEASED}：
     * 第二道栅栏（{@code WHERE status='CONSUMED'}）会命中 0 行并抛
     * {@code RESERVATION_STATE_INCONSISTENT}。此时如果订单更新没有和它同事务，
     * 订单会停在"已取消但库存没回来"的状态——库存守恒被打破，而订单看起来一切正常。</p>
     */
    @Test
    void failedCancelRollsBackOrderStatusLedgerAndOutboxTogether() {
        String userId = "u-atomic";
        Order order = placeOrder(userId, SKU, 1, "a1");
        // 破坏不变量：订单还在待付款，但预占已经不是 CONSUMED。
        jdbc.update("UPDATE commerce_schema.inventory_reservation SET status = 'RELEASED' WHERE reservation_id = ?",
                order.reservationId());

        CommerceException error = assertThrows(CommerceException.class,
                () -> cancel(userId, order.orderId(), "a1-cancel"));

        assertEquals("RESERVATION_STATE_INCONSISTENT", error.code());
        assertEquals("PENDING_PAYMENT", statusColumnOf(order.orderId()),
                "预占校验失败必须把订单状态一起回滚，不能留下'已取消但没回补库存'的订单");
        assertEquals(AIR_INITIAL_STOCK - 1, stock(SKU), "失败不得回补库存");
        assertEquals(0, count("""
                SELECT count(*) FROM commerce_schema.outbox_event WHERE event_type = 'ORDER_CANCELLED'
                """), "失败不得留下取消事件");
        assertEquals(0, count("""
                SELECT count(*) FROM commerce_schema.effect_record WHERE operation_type = 'CANCEL_ORDER'
                """), "失败不落账：ledger 记录必须一起回滚，否则重试会命中空结果");
    }

    /**
     * 取消只允许从 {@code PENDING_PAYMENT} 出发。这里把订单手工推进到 {@code PAID}
     * （模拟日后接上支付），取消必须被拒绝而不是默默当成已取消。
     */
    @Test
    void cancelRefusesOrderThatMovedBeyondPendingPayment() {
        String userId = "u-advanced";
        Order order = placeOrder(userId, SKU, 1, "adv");
        Order paid = new Order(order.orderId(), order.userId(), order.sourceSnapshotId(), order.reservationId(),
                order.quote(), OrderStatus.PAID, order.createdAt(), order.version() + 1);
        jdbc.update("""
                UPDATE commerce_schema.orders SET status = 'PAID', version = version + 1,
                       order_payload = CAST(? AS jsonb) WHERE order_id = ?
                """, json.writeValueAsString(paid), order.orderId());

        CommerceException error = assertThrows(CommerceException.class,
                () -> cancel(userId, order.orderId(), "adv-cancel"));

        assertEquals("ORDER_NOT_CANCELLABLE", error.code());
        assertEquals("PAID", statusColumnOf(order.orderId()), "拒绝必须是原子的：状态不得被改成 CANCELLED");
        assertEquals(AIR_INITIAL_STOCK - 1, stock(SKU), "拒绝不得回补库存");
        assertEquals(0, count("""
                SELECT count(*) FROM commerce_schema.effect_record WHERE operation_type = 'CANCEL_ORDER'
                """), "拒绝不得落账");
    }

    // ===== 11.6 状态机与迁移生效 ============================================

    @Test
    void cancelledReservationIsDistinguishableFromPlainRelease() {
        String userId = "u-states";
        Order order = placeOrder(userId, SKU, 1, "st1");

        cancel(userId, order.orderId(), "st1-cancel");

        assertEquals("RELEASED_BY_CANCEL", reservationStatus(order.reservationId()),
                "成交后撤销必须记为 RELEASED_BY_CANCEL");
        assertEquals(0, count("SELECT count(*) FROM commerce_schema.inventory_reservation WHERE status = 'RELEASED'"),
                "RELEASED 只用于成交前释放，二者不可混用");
    }

    /** 直接验证 V10 迁移真的生效了，而不是只躺在文件里。 */
    @Test
    void databaseRejectsUnknownOrderStatus() {
        String userId = "u-check";
        Order order = placeOrder(userId, SKU, 1, "ck");

        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE commerce_schema.orders SET status = 'TELEPORTED' WHERE order_id = ?", order.orderId()));

        // 合法状态仍必须放行：CHECK 只枚举状态名，不表达迁移合法性。
        jdbc.update("UPDATE commerce_schema.orders SET status = 'REFUND_PENDING' WHERE order_id = ?", order.orderId());
        assertEquals("REFUND_PENDING", statusColumnOf(order.orderId()));
    }

    // ===== helpers ==========================================================

    private Order placeOrder(String userId, String skuId, int quantity, String tag) {
        ConfirmableOrderSnapshot snapshot = prepare(userId, skuId, quantity, tag);
        return createOrder(userId, snapshot, tag);
    }

    private ConfirmableOrderSnapshot prepare(String userId, String skuId, int quantity, String tag) {
        String addressId = addressFor(userId);
        return inTransaction(() -> engine.prepareConfirmableOrder(
                new PrepareOrderRequest(userId, skuId, quantity, addressId, Money.cny("20000")),
                effect("prepare-" + tag, userId)));
    }

    private Order createOrder(String userId, ConfirmableOrderSnapshot snapshot, String tag) {
        ApprovalProof approval = new ApprovalProof("approval-" + tag, snapshot.snapshotId(), snapshot.summaryHash(),
                userId, java.time.Instant.now(), snapshot.expiresAt());
        return inTransaction(() -> engine.createOrder(
                new CreateOrderCommand(userId, snapshot.snapshotId(), approval), effect("create-" + tag, userId)));
    }

    private Order cancel(String userId, String orderId, String tag) {
        return inTransaction(() -> engine.cancelOrder(
                new CancelOrderCommand(orderId, userId), effect(tag, userId)));
    }

    private String addressFor(String userId) {
        return inTransaction(() -> engine.registerAddress(
                new RegisterAddressCommand(userId, "CN-EAST"), effect("addr-" + userId, userId)).addressId());
    }

    /**
     * 把引擎调用包进真实事务。理由见类注释：手工构造的引擎没有 {@code @Transactional} 代理，
     * 不包事务就等于在拿自动提交的语义去断言行锁。
     */
    private <T> T inTransaction(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }

    private void race(List<Runnable> actions, List<Throwable> failures) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(actions.size());
        CyclicBarrier barrier = new CyclicBarrier(actions.size());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Runnable action : actions) {
                futures.add(executor.submit(() -> {
                    try {
                        barrier.await(15, TimeUnit.SECONDS);
                        action.run();
                    } catch (Throwable failure) {
                        failures.add(failure);
                    }
                }));
            }
            for (Future<?> future : futures) future.get(60, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 守恒律：可用 + ACTIVE 未过期预占 + 未取消订单的 CONSUMED 预占 == 初始库存总量。
     *
     * <p>{@code RELEASED_BY_CANCEL} 与 {@code RELEASED} 的预占不参与求和——
     * 它们的库存已经回到可用量里，再加一次就是重复计数。</p>
     */
    private int conserved(String skuId) {
        Integer total = jdbc.queryForObject("""
                SELECT i.available_quantity
                     + COALESCE((SELECT SUM(r.quantity) FROM commerce_schema.inventory_reservation r
                                 WHERE r.sku_id = i.sku_id AND r.status = 'ACTIVE' AND r.expires_at > now()), 0)
                     + COALESCE((SELECT SUM(r.quantity) FROM commerce_schema.inventory_reservation r
                                 JOIN commerce_schema.orders o ON o.reservation_id = r.reservation_id
                                 WHERE r.sku_id = i.sku_id AND r.status = 'CONSUMED'
                                   AND o.status <> 'CANCELLED'), 0)
                FROM commerce_schema.inventory i WHERE i.sku_id = ?
                """, Integer.class, skuId);
        return total == null ? -1 : total;
    }

    private int stock(String skuId) {
        Integer value = jdbc.queryForObject(
                "SELECT available_quantity FROM commerce_schema.inventory WHERE sku_id = ?", Integer.class, skuId);
        return value == null ? -1 : value;
    }

    private String statusColumnOf(String orderId) {
        return jdbc.queryForObject("SELECT status FROM commerce_schema.orders WHERE order_id = ?", String.class, orderId);
    }

    private String reservationStatus(String reservationId) {
        return jdbc.queryForObject("SELECT status FROM commerce_schema.inventory_reservation WHERE reservation_id = ?",
                String.class, reservationId);
    }

    private Timestamp cancelledAt(String orderId) {
        return jdbc.queryForObject("SELECT cancelled_at FROM commerce_schema.orders WHERE order_id = ?",
                Timestamp.class, orderId);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? -1 : value;
    }

    private static EffectContext effect(String effectId, String userId) {
        return new EffectContext(effectId, effectId, "run-" + effectId, "cancel-order", 0, userId, "trace-" + effectId);
    }
}
