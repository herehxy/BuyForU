package com.buyforu.commerce.application;

import com.buyforu.commerce.port.CommerceGateway;
import com.buyforu.commerce.port.model.CommerceModels.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单元测试专用 Commerce 实现；模拟与 JDBC 引擎一致的报价、预占、审批和 effect 语义。
 * 该类位于 test 源集，绝不会作为运行时降级实现打入生产 JAR。
 */
public final class InMemoryCommerceEngine implements CommerceGateway {
    private static final Duration QUOTE_TTL = Duration.ofMinutes(5);
    private static final Duration RESERVATION_TTL = Duration.ofMinutes(15);

    private final Clock clock;
    private final Map<String, CatalogItem> catalog;
    private final Map<String, Integer> inventory = new ConcurrentHashMap<>();
    private final Map<String, Reservation> reservations = new ConcurrentHashMap<>();
    private final Map<String, ConfirmableOrderSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final EffectLedger effectLedger = new EffectLedger();
    private final AtomicLong quoteVersion = new AtomicLong();
    private final AtomicLong snapshotVersion = new AtomicLong();

    public InMemoryCommerceEngine(Clock clock, List<CatalogItem> items) {
        this.clock = clock;
        Map<String, CatalogItem> indexed = new LinkedHashMap<>();
        for (CatalogItem item : items) {
            indexed.put(item.skuId(), item);
            inventory.put(item.skuId(), item.initialStock());
        }
        this.catalog = Map.copyOf(indexed);
    }

    public static InMemoryCommerceEngine seeded(Clock clock) {
        // 固定目录数据使工作流测试可以稳定断言候选、价格和库存数量。
        return new InMemoryCommerceEngine(clock, List.of(
                new CatalogItem("p-100", "sku-air-16", "Aurora Air 16 轻薄本", "Aurora", "laptop",
                        Map.of("memory", "16GB", "storage", "1TB", "weight", "1.3kg"), new BigDecimal("4999.00"), 8),
                new CatalogItem("p-200", "sku-pro-16", "Pine Pro 16 商务本", "Pine", "laptop",
                        Map.of("memory", "16GB", "storage", "512GB", "weight", "1.45kg"), new BigDecimal("4599.00"), 5),
                new CatalogItem("p-300", "sku-max-32", "Nova Max 32 性能本", "Nova", "laptop",
                        Map.of("memory", "32GB", "storage", "1TB", "weight", "2.1kg"), new BigDecimal("6299.00"), 3)
        ));
    }

    @Override
    public List<DeliveryAddress> listAddresses(String userId) {
        return List.of(new DeliveryAddress("address-1", userId, "CN-EAST", 1));
    }

    @Override
    public SearchResult searchProducts(SearchRequest request) {
        String q = request.query() == null ? "" : request.query().toLowerCase();
        List<ProductCandidate> matches = catalog.values().stream()
                .filter(item -> request.category() == null || request.category().isBlank()
                        || request.category().equalsIgnoreCase(item.category()))
                .filter(item -> q.isBlank() || item.name().toLowerCase().contains(q)
                        || item.category().toLowerCase().contains(q))
                .filter(item -> request.excludedBrands().stream().noneMatch(b -> b.equalsIgnoreCase(item.brand())))
                .filter(item -> request.requiredAttributes().entrySet().stream()
                        .allMatch(e -> e.getValue().equalsIgnoreCase(item.attributes().get(e.getKey()))))
                .filter(item -> request.budgetMax() == null
                        || payable(item.unitPrice(), request.quantity())
                        .compareTo(request.budgetMax().amount()) <= 0)
                .sorted(Comparator.comparing(CatalogItem::unitPrice))
                .limit(request.limit())
                .map(item -> new ProductCandidate(item.productId(), item.skuId(), item.name(), item.brand(),
                        item.attributes(), new Money(item.unitPrice(), "CNY"), available(item.skuId()),
                        LocalDate.now(clock).plusDays(1)))
                .toList();
        return new SearchResult(matches, clock.instant());
    }

    @Override
    public Quote quote(QuoteRequest request) {
        CatalogItem item = requireItem(request.skuId());
        Instant now = clock.instant();
        PricedItems priced = priceItems(item.unitPrice(), request.quantity());
        return new Quote(UUID.randomUUID().toString(), quoteVersion.incrementAndGet(), request.skuId(), request.quantity(),
                new Money(priced.itemAmount(), "CNY"), priced.discounts(), new Money(priced.shipping(), "CNY"),
                new Money(priced.payable(), "CNY"),
                LocalDate.now(clock).plusDays(1), now, now.plus(QUOTE_TTL));
    }

    @Override
    public DeliveryAddress registerAddress(RegisterAddressCommand command, EffectContext effectContext) {
        return new DeliveryAddress(UUID.randomUUID().toString(), command.userId(), command.zoneCode(), 1);
    }

    @Override
    public synchronized ConfirmableOrderSnapshot prepareConfirmableOrder(
            PrepareOrderRequest request, EffectContext effectContext) {
        String requestHash = hash("prepare", request.userId(), request.skuId(), String.valueOf(request.quantity()),
                request.addressId());
        return effectLedger.execute(effectContext, "PREPARE_CONFIRMABLE_ORDER", requestHash, () -> {
            expireReservations();
            requireAvailable(request.skuId(), request.quantity());
            Quote quote = quote(new QuoteRequest(request.skuId(), request.quantity(), request.userId(), request.addressId()));
            if (request.budgetMax() != null
                    && quote.payableAmount().amount().compareTo(request.budgetMax().amount()) > 0) {
                throw new CommerceException("BUDGET_EXCEEDED",
                        "payable " + quote.payableAmount().amount() + " exceeds budget "
                                + request.budgetMax().amount());
            }
            inventory.compute(request.skuId(), (ignored, stock) -> stock - request.quantity());
            Instant now = clock.instant();
            Reservation reservation = new Reservation(UUID.randomUUID().toString(), request.skuId(), request.quantity(),
                    "warehouse-default", ReservationStatus.ACTIVE, now, now.plus(RESERVATION_TTL));
            reservations.put(reservation.reservationId(), reservation);

            String snapshotId = UUID.randomUUID().toString();
            Instant expiresAt = min(quote.expiresAt(), reservation.expiresAt());
            String summaryHash = snapshotHash(snapshotId, request, quote, reservation, expiresAt);
            ConfirmableOrderSnapshot snapshot = new ConfirmableOrderSnapshot(snapshotId, request.userId(),
                    request.addressId(), quote, reservation, summaryHash, snapshotVersion.incrementAndGet(), now, expiresAt);
            snapshots.put(snapshotId, snapshot);
            return snapshot;
        });
    }

    @Override
    public synchronized void releaseReservation(String reservationId, EffectContext effectContext) {
        String requestHash = hash("release", reservationId, effectContext.userId());
        effectLedger.execute(effectContext, "RELEASE_RESERVATION", requestHash, () -> {
            Reservation reservation = requireReservation(reservationId);
            if (reservation.status() == ReservationStatus.ACTIVE) {
                inventory.merge(reservation.skuId(), reservation.quantity(), Integer::sum);
                reservations.put(reservationId, withStatus(reservation, ReservationStatus.RELEASED));
            }
            return Boolean.TRUE;
        });
    }

    @Override
    public synchronized Order createOrder(CreateOrderCommand command, EffectContext effectContext) {
        ApprovalProof approval = command.approval();
        String requestHash = hash("create-order", command.userId(), command.snapshotId(),
                approval == null ? "" : approval.approvalId(), approval == null ? "" : approval.expectedSummaryHash());
        return effectLedger.execute(effectContext, "CREATE_ORDER", requestHash, () -> {
            expireReservations();
            ConfirmableOrderSnapshot snapshot = requireSnapshot(command.snapshotId());
            validateApproval(command, snapshot);
            Reservation reservation = requireReservation(snapshot.reservation().reservationId());
            if (reservation.status() != ReservationStatus.ACTIVE || !clock.instant().isBefore(reservation.expiresAt())) {
                throw new CommerceException("RESERVATION_NOT_ACTIVE", "reservation is no longer active");
            }
            if (!clock.instant().isBefore(snapshot.expiresAt())) {
                throw new CommerceException("SNAPSHOT_EXPIRED", "confirmable snapshot expired");
            }
            reservations.put(reservation.reservationId(), withStatus(reservation, ReservationStatus.CONSUMED));
            Order order = new Order(UUID.randomUUID().toString(), command.userId(), snapshot.snapshotId(),
                    reservation.reservationId(), snapshot.quote(), OrderStatus.PENDING_PAYMENT, clock.instant(), 1L);
            orders.put(order.orderId(), order);
            return order;
        });
    }

    public int availableStock(String skuId) {
        expireReservations();
        return inventory.getOrDefault(skuId, 0);
    }

    public int orderCount() {
        return orders.size();
    }

    private void validateApproval(CreateOrderCommand command, ConfirmableOrderSnapshot snapshot) {
        ApprovalProof approval = command.approval();
        if (approval == null) throw new CommerceException("APPROVAL_REQUIRED", "approval is required");
        if (!snapshot.userId().equals(command.userId()) || !command.userId().equals(approval.approvedBy())) {
            throw new CommerceException("APPROVAL_USER_MISMATCH", "approval belongs to another user");
        }
        if (!snapshot.snapshotId().equals(approval.snapshotId())
                || !snapshot.summaryHash().equals(approval.expectedSummaryHash())) {
            throw new CommerceException("APPROVAL_SNAPSHOT_MISMATCH", "approval does not match snapshot");
        }
        if (approval.expiresAt() == null || !clock.instant().isBefore(approval.expiresAt())) {
            throw new CommerceException("APPROVAL_EXPIRED", "approval expired");
        }
    }

    private CatalogItem requireItem(String skuId) {
        CatalogItem item = catalog.get(skuId);
        if (item == null) throw new CommerceException("SKU_NOT_FOUND", "unknown sku: " + skuId);
        return item;
    }

    private ConfirmableOrderSnapshot requireSnapshot(String snapshotId) {
        ConfirmableOrderSnapshot snapshot = snapshots.get(snapshotId);
        if (snapshot == null) throw new CommerceException("SNAPSHOT_NOT_FOUND", "snapshot not found");
        return snapshot;
    }

    private Reservation requireReservation(String reservationId) {
        Reservation reservation = reservations.get(reservationId);
        if (reservation == null) throw new CommerceException("RESERVATION_NOT_FOUND", "reservation not found");
        return reservation;
    }

    private void requireAvailable(String skuId, int quantity) {
        requireItem(skuId);
        if (inventory.getOrDefault(skuId, 0) < quantity) {
            throw new CommerceException("OUT_OF_STOCK", "insufficient inventory");
        }
    }

    private boolean available(String skuId) {
        return inventory.getOrDefault(skuId, 0) > 0;
    }

    private void expireReservations() {
        Instant now = clock.instant();
        for (Reservation reservation : List.copyOf(reservations.values())) {
            if (reservation.status() == ReservationStatus.ACTIVE && !now.isBefore(reservation.expiresAt())) {
                inventory.merge(reservation.skuId(), reservation.quantity(), Integer::sum);
                reservations.put(reservation.reservationId(), withStatus(reservation, ReservationStatus.EXPIRED));
            }
        }
    }

    private Reservation withStatus(Reservation value, ReservationStatus status) {
        return new Reservation(value.reservationId(), value.skuId(), value.quantity(), value.warehouseId(), status,
                value.createdAt(), value.expiresAt());
    }

    private String snapshotHash(String snapshotId, PrepareOrderRequest request, Quote quote,
                                Reservation reservation, Instant expiresAt) {
        return hash(snapshotId, request.userId(), request.addressId(), request.skuId(),
                String.valueOf(request.quantity()), quote.payableAmount().amount().toPlainString(),
                quote.deliveryPromise().toString(), reservation.reservationId(), expiresAt.toString());
    }

    private static String hash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(String.join("\u001f", values).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Instant min(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private PricedItems priceItems(BigDecimal unitPrice, int quantity) {
        BigDecimal itemAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        List<DiscountLine> discounts = new ArrayList<>();
        BigDecimal discount = BigDecimal.ZERO;
        if (itemAmount.compareTo(new BigDecimal("5000")) >= 0) {
            discount = new BigDecimal("200.00");
            discounts.add(new DiscountLine("FULL_5000_200", "满 5000 减 200", new Money(discount, "CNY")));
        }
        BigDecimal shipping = itemAmount.compareTo(new BigDecimal("99")) >= 0
                ? BigDecimal.ZERO : new BigDecimal("10.00");
        return new PricedItems(itemAmount, discounts, shipping, itemAmount.subtract(discount).add(shipping));
    }

    private BigDecimal payable(BigDecimal unitPrice, int quantity) {
        return priceItems(unitPrice, quantity).payable();
    }

    private record PricedItems(BigDecimal itemAmount, List<DiscountLine> discounts,
                               BigDecimal shipping, BigDecimal payable) { }

    public record CatalogItem(String productId, String skuId, String name, String brand, String category,
                              Map<String, String> attributes, BigDecimal unitPrice, int initialStock) {
    }
}
