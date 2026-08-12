package com.buyforu.commerce.port.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 跨 Commerce 端口传递的领域值对象集合。
 *
 * <p>这些对象是 Agent 与 Commerce 之间的结构化契约。金额、报价、预占和订单由 Commerce
 * 创建；Agent 只能消费结果，不能自行构造一个“更便宜”的最终报价。</p>
 */
public final class CommerceModels {
    private CommerceModels() {
    }

    // ===== 通用金额与商品只读模型 =============================================

    public record Money(BigDecimal amount, String currency) {
        public Money {
            Objects.requireNonNull(amount, "amount");
            currency = Objects.requireNonNullElse(currency, "CNY");
            if (!"CNY".equals(currency)) throw new IllegalArgumentException("only CNY is supported");
            if (amount.signum() < 0) throw new IllegalArgumentException("money cannot be negative");
            if (amount.scale() > 2) amount = amount.setScale(2, java.math.RoundingMode.HALF_UP);
        }

        public static Money cny(String amount) {
            return new Money(new BigDecimal(amount), "CNY");
        }
    }

    public record ProductCandidate(
            String productId,
            String skuId,
            String name,
            String brand,
            Map<String, String> attributes,
            Money displayPrice,
            boolean available,
            LocalDate deliveryDate
    ) {
    }

    public record SearchRequest(
            String userId,
            String query,
            String category,
            Money budgetMax,
            List<String> excludedBrands,
            Map<String, String> requiredAttributes,
            String addressId,
            LocalDate deliveryBy,
            int quantity,
            int limit
    ) {
        public SearchRequest {
            Objects.requireNonNull(userId, "userId");
            if (userId.isBlank()) throw new IllegalArgumentException("userId is required");
            excludedBrands = excludedBrands == null ? List.of() : List.copyOf(excludedBrands);
            requiredAttributes = requiredAttributes == null ? Map.of() : Map.copyOf(requiredAttributes);
            if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
            if (quantity > 99) throw new IllegalArgumentException("quantity cannot exceed 99");
            limit = limit <= 0 ? 10 : Math.min(limit, 50);
        }
    }

    public record SearchResult(List<ProductCandidate> candidates, Instant observedAt) {
        public SearchResult {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            observedAt = Objects.requireNonNullElseGet(observedAt, Instant::now);
        }
    }

    // ===== 报价、优惠与履约模型 ===============================================

    public record QuoteRequest(String skuId, int quantity, String userId, String addressId) {
        public QuoteRequest {
            Objects.requireNonNull(skuId, "skuId");
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(addressId, "addressId");
            if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
            if (quantity > 99) throw new IllegalArgumentException("quantity cannot exceed 99");
        }
    }

    public record DiscountLine(String code, String description, Money amount) {
    }

    public record Quote(
            String quoteId,
            long quoteVersion,
            String skuId,
            int quantity,
            Money itemAmount,
            List<DiscountLine> discounts,
            Money shippingFee,
            Money payableAmount,
            LocalDate deliveryPromise,
            Instant observedAt,
            Instant expiresAt
    ) {
        public Quote {
            discounts = discounts == null ? List.of() : List.copyOf(discounts);
        }
    }

    public record PrepareOrderRequest(
            String userId,
            String skuId,
            int quantity,
            String addressId
    ) {
        public PrepareOrderRequest {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(skuId, "skuId");
            Objects.requireNonNull(addressId, "addressId");
            if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
            if (quantity > 99) throw new IllegalArgumentException("quantity cannot exceed 99");
        }
    }

    public record RegisterAddressCommand(String userId, String zoneCode) {
        public RegisterAddressCommand {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(zoneCode, "zoneCode");
            if (userId.isBlank()) throw new IllegalArgumentException("userId is required");
            if (zoneCode.isBlank()) throw new IllegalArgumentException("zoneCode is required");
        }
    }

    public record DeliveryAddress(String addressId, String userId, String zoneCode, int deliveryDays) {
    }

    // ===== 库存预占、确认快照、人工证明与订单 =================================

    public record Reservation(
            String reservationId,
            String skuId,
            int quantity,
            String warehouseId,
            ReservationStatus status,
            Instant createdAt,
            Instant expiresAt
    ) {
    }

    public enum ReservationStatus { ACTIVE, CONSUMED, RELEASED, EXPIRED }

    /**
     * 最终确认前冻结给用户看的交易快照。
     * summaryHash 将用户、地址、SKU、金额、履约、预占和有效期绑定在一起，避免确认后被替换。
     */
    public record ConfirmableOrderSnapshot(
            String snapshotId,
            String userId,
            String addressId,
            Quote quote,
            Reservation reservation,
            String summaryHash,
            long snapshotVersion,
            Instant createdAt,
            Instant expiresAt
    ) {
    }

    public record ApprovalProof(
            String approvalId,
            String snapshotId,
            String expectedSummaryHash,
            String approvedBy,
            Instant approvedAt,
            Instant expiresAt
    ) {
    }

    public record CreateOrderCommand(
            String userId,
            String snapshotId,
            ApprovalProof approval
    ) {
    }

    public record Order(
            String orderId,
            String userId,
            String sourceSnapshotId,
            String reservationId,
            Quote quote,
            OrderStatus status,
            Instant createdAt,
            long version
    ) {
    }

    public enum OrderStatus {
        PENDING_PAYMENT, PAID, FULFILLING, SHIPPED, COMPLETED,
        CANCELLED, REFUND_PENDING, REFUNDED
    }

    // ===== 写操作幂等与审计上下文 =============================================

    /**
     * 所有写操作必须携带的副作用上下文。
     * effectId 标识图中的逻辑副作用，idempotencyKey 支持请求安全重试。
     */
    public record EffectContext(
            String effectId,
            String idempotencyKey,
            String runId,
            String nodeId,
            int attempt,
            String userId,
            String traceId
    ) {
        public EffectContext {
            Objects.requireNonNull(effectId, "effectId");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(userId, "userId");
            if (effectId.isBlank() || idempotencyKey.isBlank() || runId.isBlank()
                    || nodeId.isBlank() || userId.isBlank()) {
                throw new IllegalArgumentException("effect context identifiers cannot be blank");
            }
            if (attempt < 0) throw new IllegalArgumentException("effect attempt cannot be negative");
        }
    }
}
