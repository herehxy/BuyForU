package com.buyforu.commerce.application;

import com.buyforu.commerce.port.CommerceGateway;
import com.buyforu.commerce.port.model.CommerceModels.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PostgreSQL 版本的 Commerce 交易引擎，也是交易事实的唯一写入点。
 *
 * <p>事务、行锁、effect ledger 和唯一约束共同保证库存不超卖、订单不重复；
 * Agent、MCP 和前端都不能绕过本类直接决定金额或库存。</p>
 */
@Repository
public class JdbcCommerceEngine implements CommerceGateway {
    private static final Duration QUOTE_TTL = Duration.ofMinutes(5);
    private static final Duration RESERVATION_TTL = Duration.ofMinutes(15);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public JdbcCommerceEngine(JdbcTemplate jdbc, ObjectMapper json) {
        this(jdbc, json, Clock.systemUTC());
    }

    JdbcCommerceEngine(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    // ===== 只读交易能力：地址、商品搜索与权威报价 ===============================

    @Override
    public List<DeliveryAddress> listAddresses(String userId) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId is required");
        return jdbc.query("""
                SELECT a.address_id, a.user_id, a.zone_code, z.delivery_days
                FROM commerce_schema.customer_address a
                JOIN commerce_schema.delivery_zone z ON z.zone_code = a.zone_code
                WHERE a.user_id = ? AND a.active AND z.active
                ORDER BY a.created_at DESC, a.address_id DESC
                """, (result, row) -> new DeliveryAddress(result.getString(1), result.getString(2),
                result.getString(3), result.getInt(4)), userId);
    }

    @Override
    public SearchResult searchProducts(SearchRequest request) {
        LocalDate deliveryDate = resolveDeliveryDate(request.addressId(), request.userId());
        List<ProductCandidate> candidates = jdbc.query("""
                SELECT p.product_id, s.sku_id, p.name, p.brand, p.attributes::text,
                       s.unit_price, i.available_quantity
                FROM commerce_schema.product p
                JOIN commerce_schema.sku s ON s.product_id = p.product_id
                JOIN commerce_schema.inventory i ON i.sku_id = s.sku_id
                WHERE s.status = 'ACTIVE'
                  AND (? = '' OR lower(p.category) = lower(?))
                  AND (? = '' OR lower(p.name) LIKE lower('%' || ? || '%') OR lower(p.category) LIKE lower('%' || ? || '%'))
                ORDER BY s.unit_price
                """, (result, row) -> new ProductCandidate(
                        result.getString("product_id"), result.getString("sku_id"), result.getString("name"),
                        result.getString("brand"), readAttributes(result.getString("attributes")),
                        new Money(result.getBigDecimal("unit_price"), "CNY"),
                        result.getInt("available_quantity") >= request.quantity(), deliveryDate),
                normalized(request.category()), normalized(request.category()), normalized(request.query()),
                normalized(request.query()), normalized(request.query())).stream()
                .filter(candidate -> request.excludedBrands().stream()
                        .noneMatch(brand -> brand.equalsIgnoreCase(candidate.brand())))
                .filter(candidate -> request.requiredAttributes().entrySet().stream()
                        .allMatch(entry -> entry.getValue().equalsIgnoreCase(candidate.attributes().get(entry.getKey()))))
                .filter(candidate -> request.budgetMax() == null
                        || candidate.displayPrice().amount().compareTo(request.budgetMax().amount()) <= 0)
                .filter(candidate -> request.deliveryBy() == null
                        || !candidate.deliveryDate().isAfter(request.deliveryBy()))
                .filter(ProductCandidate::available)
                .limit(request.limit())
                .toList();
        return new SearchResult(candidates, clock.instant());
    }

    @Override
    public Quote quote(QuoteRequest request) {
        // 报价始终读取当前 SKU 价格、促销表和运费表，不能接受调用方传入的“最终金额”。
        LocalDate deliveryPromise = resolveDeliveryDate(request.addressId(), request.userId());
        BigDecimal unitPrice = jdbc.query("""
                SELECT unit_price FROM commerce_schema.sku WHERE sku_id = ? AND status = 'ACTIVE'
                """, (result, row) -> result.getBigDecimal(1), request.skuId()).stream().findFirst()
                .orElseThrow(() -> new CommerceException("SKU_NOT_FOUND", "unknown sku: " + request.skuId()));
        Instant now = clock.instant();
        BigDecimal itemAmount = unitPrice.multiply(BigDecimal.valueOf(request.quantity()));
        List<DiscountLine> discounts = new ArrayList<>();
        BigDecimal discount = BigDecimal.ZERO;
        List<PromotionRow> promotions = jdbc.query("""
                SELECT promotion_code, description, discount_amount
                FROM commerce_schema.promotion_rule
                WHERE active AND starts_at <= ? AND ends_at > ? AND minimum_spend <= ?
                ORDER BY priority DESC, discount_amount DESC, promotion_code
                LIMIT 1
                """, (result, row) -> new PromotionRow(result.getString(1), result.getString(2),
                result.getBigDecimal(3)), Timestamp.from(now), Timestamp.from(now), itemAmount);
        if (!promotions.isEmpty()) {
            PromotionRow promotion = promotions.getFirst();
            discount = promotion.discountAmount().min(itemAmount);
            discounts.add(new DiscountLine(promotion.code(), promotion.description(), new Money(discount, "CNY")));
        }
        ShippingRule shippingRule = jdbc.query("""
                SELECT free_shipping_threshold, standard_fee
                FROM commerce_schema.shipping_rule WHERE active
                ORDER BY rule_code LIMIT 1
                """, (result, row) -> new ShippingRule(result.getBigDecimal(1), result.getBigDecimal(2)))
                .stream().findFirst()
                .orElseThrow(() -> new CommerceException("SHIPPING_RULE_MISSING", "no active shipping rule"));
        BigDecimal shipping = itemAmount.compareTo(shippingRule.freeShippingThreshold()) >= 0
                ? BigDecimal.ZERO : shippingRule.standardFee();
        return new Quote(UUID.randomUUID().toString(), nextVersion("quote_version_seq"),
                request.skuId(), request.quantity(),
                new Money(itemAmount, "CNY"), discounts, new Money(shipping, "CNY"),
                new Money(itemAmount.subtract(discount).add(shipping), "CNY"),
                deliveryPromise, now, now.plus(QUOTE_TTL));
    }

    @Override
    @Transactional
    public DeliveryAddress registerAddress(RegisterAddressCommand command, EffectContext effect) {
        // ===== 幂等写能力：地址登记 ============================================
        assertEffectUser(effect, command.userId());
        String requestHash = hash("register-address", command.userId(), command.zoneCode());
        DeliveryAddress replay = beginEffect(effect, "REGISTER_ADDRESS", requestHash, DeliveryAddress.class);
        if (replay != null) return replay;
        Integer deliveryDays = jdbc.query("""
                SELECT delivery_days FROM commerce_schema.delivery_zone
                WHERE zone_code = ? AND active
                """, (result, row) -> result.getInt(1), command.zoneCode()).stream().findFirst()
                .orElseThrow(() -> new CommerceException("DELIVERY_ZONE_NOT_FOUND", "unknown delivery zone"));
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                preparedStatement -> preparedStatement.setString(1,
                        "address\u001f" + command.userId() + "\u001f" + command.zoneCode()),
                resultSet -> null);
        DeliveryAddress existing = jdbc.query("""
                SELECT address_id, user_id, zone_code
                FROM commerce_schema.customer_address
                WHERE user_id = ? AND zone_code = ? AND active
                FOR UPDATE
                """, (result, row) -> new DeliveryAddress(result.getString(1), result.getString(2),
                result.getString(3), deliveryDays), command.userId(), command.zoneCode())
                .stream().findFirst().orElse(null);
        if (existing != null) {
            completeEffect(effect, existing.addressId(), existing);
            return existing;
        }
        DeliveryAddress address = new DeliveryAddress(UUID.randomUUID().toString(), command.userId(),
                command.zoneCode(), deliveryDays);
        jdbc.update("""
                INSERT INTO commerce_schema.customer_address(address_id, user_id, zone_code, active)
                VALUES (?, ?, ?, TRUE)
                """, address.addressId(), address.userId(), address.zoneCode());
        completeEffect(effect, address.addressId(), address);
        return address;
    }

    @Override
    @Transactional
    public ConfirmableOrderSnapshot prepareConfirmableOrder(PrepareOrderRequest request, EffectContext effect) {
        // ===== 确认前交易准备：重新报价、扣减可售库存、生成预占和不可篡改快照 =====
        assertEffectUser(effect, request.userId());
        String requestHash = hash("prepare", request.userId(), request.skuId(),
                String.valueOf(request.quantity()), request.addressId());
        ConfirmableOrderSnapshot replay = beginEffect(effect, "PREPARE_CONFIRMABLE_ORDER", requestHash,
                ConfirmableOrderSnapshot.class);
        if (replay != null) return replay;
        expireReservationsLocked();
        // FOR UPDATE 串行化同一 SKU 的并发预占；检查与扣减处于同一事务，避免先查后扣导致超卖。
        Integer stock = jdbc.query("""
                SELECT available_quantity FROM commerce_schema.inventory WHERE sku_id = ? FOR UPDATE
                """, (result, row) -> result.getInt(1), request.skuId()).stream().findFirst()
                .orElseThrow(() -> new CommerceException("SKU_NOT_FOUND", "unknown sku: " + request.skuId()));
        if (stock < request.quantity()) throw new CommerceException("OUT_OF_STOCK", "insufficient inventory");

        Quote quote = quote(new QuoteRequest(request.skuId(), request.quantity(), request.userId(), request.addressId()));
        jdbc.update("""
                UPDATE commerce_schema.inventory
                SET available_quantity = available_quantity - ?, version = version + 1 WHERE sku_id = ?
                """, request.quantity(), request.skuId());
        Instant now = clock.instant();
        Reservation reservation = new Reservation(UUID.randomUUID().toString(), request.skuId(), request.quantity(),
                "warehouse-default", ReservationStatus.ACTIVE, now, now.plus(RESERVATION_TTL));
        jdbc.update("""
                INSERT INTO commerce_schema.inventory_reservation
                    (reservation_id, sku_id, quantity, status, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, reservation.reservationId(), reservation.skuId(), reservation.quantity(),
                reservation.status().name(), Timestamp.from(reservation.expiresAt()), Timestamp.from(now));

        String snapshotId = UUID.randomUUID().toString();
        Instant expiresAt = quote.expiresAt().isBefore(reservation.expiresAt())
                ? quote.expiresAt() : reservation.expiresAt();
        // 摘要覆盖用户实际确认的全部交易事实，批准请求必须原样带回此摘要。
        String summaryHash = hash(snapshotId, request.userId(), request.addressId(), request.skuId(),
                String.valueOf(request.quantity()), quote.payableAmount().amount().toPlainString(),
                quote.deliveryPromise().toString(), reservation.reservationId(), expiresAt.toString());
        ConfirmableOrderSnapshot snapshot = new ConfirmableOrderSnapshot(snapshotId, request.userId(),
                request.addressId(), quote, reservation, summaryHash,
                nextVersion("snapshot_version_seq"), now, expiresAt);
        jdbc.update("""
                INSERT INTO commerce_schema.confirmable_snapshot
                    (snapshot_id, user_id, reservation_id, summary_hash, snapshot, expires_at, created_at)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                """, snapshotId, request.userId(), reservation.reservationId(), summaryHash,
                json.writeValueAsString(snapshot), Timestamp.from(expiresAt), Timestamp.from(now));
        completeEffect(effect, snapshotId, snapshot);
        return snapshot;
    }

    @Override
    @Transactional
    public void releaseReservation(String reservationId, EffectContext effect) {
        // 释放只对 ACTIVE 预占归还一次库存；重复释放由 effect ledger 或状态判断吸收。
        String requestHash = hash("release", reservationId, effect.userId());
        Boolean replay = beginEffect(effect, "RELEASE_RESERVATION", requestHash, Boolean.class);
        if (replay != null) return;
        List<ReservationRow> rows = jdbc.query("""
                SELECT sku_id, quantity, status FROM commerce_schema.inventory_reservation
                WHERE reservation_id = ? FOR UPDATE
                """, (result, row) -> new ReservationRow(result.getString(1), result.getInt(2), result.getString(3)),
                reservationId);
        if (rows.isEmpty()) throw new CommerceException("RESERVATION_NOT_FOUND", "reservation not found");
        ReservationRow row = rows.getFirst();
        String owner = jdbc.query("""
                SELECT user_id FROM commerce_schema.confirmable_snapshot
                WHERE reservation_id = ?
                """, (result, index) -> result.getString(1), reservationId).stream().findFirst()
                .orElseThrow(() -> new CommerceException("SNAPSHOT_NOT_FOUND", "reservation has no snapshot owner"));
        if (!effect.userId().equals(owner)) {
            throw new CommerceException("RESERVATION_USER_MISMATCH", "reservation belongs to another user");
        }
        if (ReservationStatus.ACTIVE.name().equals(row.status())) {
            jdbc.update("UPDATE commerce_schema.inventory_reservation SET status = 'RELEASED' WHERE reservation_id = ?",
                    reservationId);
            jdbc.update("""
                    UPDATE commerce_schema.inventory SET available_quantity = available_quantity + ?, version = version + 1
                    WHERE sku_id = ?
                    """, row.quantity(), row.skuId());
        }
        completeEffect(effect, reservationId, Boolean.TRUE);
    }

    @Override
    @Transactional
    public Order createOrder(CreateOrderCommand command, EffectContext effect) {
        // ===== 最终下单：验证人工证明、消费预占、写订单与 Outbox ===============
        assertEffectUser(effect, command.userId());
        ApprovalProof approval = command.approval();
        String requestHash = hash("create-order", command.userId(), command.snapshotId(),
                approval == null ? "" : approval.approvalId(), approval == null ? "" : approval.expectedSummaryHash());
        Order replay = beginEffect(effect, "CREATE_ORDER", requestHash, Order.class);
        if (replay != null) return replay;
        expireReservationsLocked();

        ConfirmableOrderSnapshot snapshot = jdbc.query("""
                SELECT snapshot::text FROM commerce_schema.confirmable_snapshot
                WHERE snapshot_id = ? FOR UPDATE
                """, (result, row) -> json.readValue(result.getString(1), ConfirmableOrderSnapshot.class),
                command.snapshotId()).stream().findFirst()
                .orElseThrow(() -> new CommerceException("SNAPSHOT_NOT_FOUND", "snapshot not found"));
        validateApprovalIdentity(command, snapshot);
        // 即使客户端换了新的网络幂等键，只要来源快照相同，也只能存在一个订单。
        Order existingOrder = jdbc.query("""
                SELECT order_payload::text FROM commerce_schema.orders
                WHERE source_snapshot_id = ?
                FOR UPDATE
                """, (result, row) -> json.readValue(result.getString(1), Order.class), snapshot.snapshotId())
                .stream().findFirst().orElse(null);
        if (existingOrder != null) {
            completeEffect(effect, existingOrder.orderId(), existingOrder);
            return existingOrder;
        }
        validateApprovalTime(command.approval());
        String status = jdbc.query("""
                SELECT status FROM commerce_schema.inventory_reservation WHERE reservation_id = ? FOR UPDATE
                """, (result, row) -> result.getString(1), snapshot.reservation().reservationId()).stream().findFirst()
                .orElseThrow(() -> new CommerceException("RESERVATION_NOT_FOUND", "reservation not found"));
        if (!ReservationStatus.ACTIVE.name().equals(status) || !clock.instant().isBefore(snapshot.expiresAt())) {
            throw new CommerceException("RESERVATION_NOT_ACTIVE", "reservation or snapshot is no longer active");
        }
        jdbc.update("UPDATE commerce_schema.inventory_reservation SET status = 'CONSUMED' WHERE reservation_id = ?",
                snapshot.reservation().reservationId());
        Order order = new Order(UUID.randomUUID().toString(), command.userId(), snapshot.snapshotId(),
                snapshot.reservation().reservationId(), snapshot.quote(), OrderStatus.PENDING_PAYMENT,
                clock.instant(), 1L);
        jdbc.update("""
                INSERT INTO commerce_schema.orders
                    (order_id, user_id, source_snapshot_id, reservation_id, status, order_payload, version, created_at)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                """, order.orderId(), order.userId(), order.sourceSnapshotId(), order.reservationId(),
                order.status().name(), json.writeValueAsString(order), order.version(), Timestamp.from(order.createdAt()));
        // 订单与 Outbox 在同一事务写入：不会出现订单成功但业务事件永久丢失的状态。
        jdbc.update("""
                INSERT INTO commerce_schema.outbox_event
                    (event_id, aggregate_type, aggregate_id, event_type, payload, status)
                VALUES (?, 'ORDER', ?, 'ORDER_CREATED', CAST(? AS jsonb), 'PENDING')
                """, UUID.randomUUID().toString(), order.orderId(), json.writeValueAsString(order));
        completeEffect(effect, order.orderId(), order);
        return order;
    }

    private LocalDate resolveDeliveryDate(String addressId, String expectedUserId) {
        if (addressId == null || addressId.isBlank()) {
            throw new CommerceException("ADDRESS_REQUIRED", "a delivery address is required");
        }
        List<AddressRow> rows = jdbc.query("""
                SELECT a.user_id, z.delivery_days
                FROM commerce_schema.customer_address a
                JOIN commerce_schema.delivery_zone z ON z.zone_code = a.zone_code
                WHERE a.address_id = ? AND a.active AND z.active
                """, (result, row) -> new AddressRow(result.getString(1), result.getInt(2)), addressId);
        if (rows.isEmpty()) throw new CommerceException("ADDRESS_NOT_SERVICEABLE", "address is unknown or not serviceable");
        AddressRow row = rows.getFirst();
        if (expectedUserId != null && !expectedUserId.equals(row.userId())) {
            throw new CommerceException("ADDRESS_USER_MISMATCH", "address belongs to another user");
        }
        return LocalDate.now(clock).plusDays(row.deliveryDays());
    }

    private <T> T beginEffect(EffectContext effect, String operation, String requestHash, Class<T> type) {
        // ===== 通用一致性工具：effect ledger、到期回收、版本和摘要 ===============
        // advisory lock 先串行化同一幂等键；requestHash 再防止同一键被误用于不同业务请求。
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                preparedStatement -> preparedStatement.setString(1, effect.idempotencyKey()),
                resultSet -> null);
        List<EffectRow> rows = jdbc.query("""
                SELECT operation_type, request_hash, status, result_payload::text
                FROM commerce_schema.effect_record
                WHERE effect_id = ? OR idempotency_key = ? FOR UPDATE
                """, (result, row) -> new EffectRow(result.getString(1), result.getString(2),
                        result.getString(3), result.getString(4)), effect.effectId(), effect.idempotencyKey());
        if (!rows.isEmpty()) {
            EffectRow row = rows.getFirst();
            if (!operation.equals(row.operation()) || !requestHash.equals(row.requestHash())) {
                throw new CommerceException("EFFECT_CONFLICT", "effect key was reused for another request");
            }
            if ("COMPLETED".equals(row.status())) return json.readValue(row.result(), type);
            throw new CommerceException("EFFECT_IN_PROGRESS", "effect is already in progress");
        }
        jdbc.update("""
                INSERT INTO commerce_schema.effect_record
                    (effect_id, operation_type, idempotency_key, request_hash, status)
                VALUES (?, ?, ?, ?, 'PENDING')
                """, effect.effectId(), operation, effect.idempotencyKey(), requestHash);
        return null;
    }

    private void completeEffect(EffectContext effect, String resourceId, Object result) {
        jdbc.update("""
                UPDATE commerce_schema.effect_record
                SET status = 'COMPLETED', resource_id = ?, result_payload = CAST(? AS jsonb), completed_at = now()
                WHERE effect_id = ?
                """, resourceId, json.writeValueAsString(result), effect.effectId());
    }

    @Transactional
    public int expireReservationsNow() {
        return expireReservationsLocked();
    }

    private int expireReservationsLocked() {
        // 先锁住所有到期 ACTIVE 记录并归还库存，最后统一标记 EXPIRED；整个过程是一个事务。
        List<ReservationRow> expired = jdbc.query("""
                SELECT sku_id, quantity, status FROM commerce_schema.inventory_reservation
                WHERE status = 'ACTIVE' AND expires_at <= ? FOR UPDATE
                """, (result, row) -> new ReservationRow(result.getString(1), result.getInt(2), result.getString(3)),
                Timestamp.from(clock.instant()));
        for (ReservationRow row : expired) {
            jdbc.update("""
                    UPDATE commerce_schema.inventory SET available_quantity = available_quantity + ?, version = version + 1
                    WHERE sku_id = ?
                    """, row.quantity(), row.skuId());
        }
        return jdbc.update("""
                UPDATE commerce_schema.inventory_reservation SET status = 'EXPIRED'
                WHERE status = 'ACTIVE' AND expires_at <= ?
                """, Timestamp.from(clock.instant()));
    }

    private long nextVersion(String sequence) {
        if (!List.of("quote_version_seq", "snapshot_version_seq").contains(sequence)) {
            throw new IllegalArgumentException("unsupported version sequence");
        }
        Long value = jdbc.queryForObject("SELECT nextval('commerce_schema." + sequence + "')", Long.class);
        if (value == null) throw new IllegalStateException("database did not generate a version");
        return value;
    }

    private static void assertEffectUser(EffectContext effect, String commandUserId) {
        if (!effect.userId().equals(commandUserId)) {
            throw new CommerceException("EFFECT_USER_MISMATCH", "effect belongs to another user");
        }
    }

    private void validateApprovalIdentity(CreateOrderCommand command, ConfirmableOrderSnapshot snapshot) {
        ApprovalProof approval = command.approval();
        if (approval == null) throw new CommerceException("APPROVAL_REQUIRED", "approval is required");
        if (!snapshot.userId().equals(command.userId()) || !command.userId().equals(approval.approvedBy())) {
            throw new CommerceException("APPROVAL_USER_MISMATCH", "approval belongs to another user");
        }
        if (!snapshot.snapshotId().equals(approval.snapshotId())
                || !snapshot.summaryHash().equals(approval.expectedSummaryHash())) {
            throw new CommerceException("APPROVAL_SNAPSHOT_MISMATCH", "approval does not match snapshot");
        }
    }

    private void validateApprovalTime(ApprovalProof approval) {
        if (approval.expiresAt() == null || !clock.instant().isBefore(approval.expiresAt())) {
            throw new CommerceException("APPROVAL_EXPIRED", "approval expired");
        }
        if (approval.approvedAt() == null || approval.approvedAt().isAfter(clock.instant())
                || approval.approvedAt().isAfter(approval.expiresAt())) {
            throw new CommerceException("APPROVAL_TIME_INVALID", "approval timestamp is invalid");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readAttributes(String value) {
        return json.readValue(value, Map.class);
    }

    private static String normalized(String value) {
        return value == null ? "" : value;
    }

    private static String hash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(String.join("\u001f", values)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record ReservationRow(String skuId, int quantity, String status) { }
    private record EffectRow(String operation, String requestHash, String status, String result) { }
    private record AddressRow(String userId, int deliveryDays) { }
    private record PromotionRow(String code, String description, BigDecimal discountAmount) { }
    private record ShippingRule(BigDecimal freeShippingThreshold, BigDecimal standardFee) { }
}
