package com.buyforu.commerce.port.model;

import com.buyforu.commerce.port.model.CommerceModels.*;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 Commerce 端口值对象的构造校验和防御性不变量。
 */
class CommerceModelsTest {

    @Nested
    class MoneyValidation {
        @Test
        void rejectsNullAmount() {
            assertThrows(NullPointerException.class, () -> new Money(null, "CNY"));
        }

        @Test
        void rejectsNegativeAmount() {
            assertThrows(IllegalArgumentException.class,
                    () -> new Money(new BigDecimal("-1"), "CNY"));
        }

        @Test
        void rejectsNonCnyCurrency() {
            assertThrows(IllegalArgumentException.class,
                    () -> new Money(BigDecimal.TEN, "USD"));
        }

        @Test
        void defaultsToCnyWhenCurrencyNull() {
            Money money = new Money(BigDecimal.TEN, null);
            assertEquals("CNY", money.currency());
        }

        @Test
        void roundsToTwoDecimalPlaces() {
            Money money = Money.cny("10.999");
            assertEquals(new BigDecimal("11.00"), money.amount());
        }

        @Test
        void acceptsZeroAmount() {
            Money money = Money.cny("0");
            assertEquals(BigDecimal.ZERO, money.amount());
        }

        @Test
        void factoryMethodCreatesCny() {
            Money money = Money.cny("99.99");
            assertEquals(new BigDecimal("99.99"), money.amount());
            assertEquals("CNY", money.currency());
        }
    }

    @Nested
    class SearchRequestValidation {
        @Test
        void rejectsNullUserId() {
            assertThrows(NullPointerException.class, () ->
                    new SearchRequest(null, "laptop", "laptop", null, List.of(), Map.of(), "addr-1", null, 1, 10));
        }

        @Test
        void rejectsBlankUserId() {
            assertThrows(IllegalArgumentException.class, () ->
                    new SearchRequest("", "laptop", "laptop", null, List.of(), Map.of(), "addr-1", null, 1, 10));
        }

        @Test
        void rejectsZeroQuantity() {
            assertThrows(IllegalArgumentException.class, () ->
                    new SearchRequest("user-1", "laptop", "laptop", null, List.of(), Map.of(), "addr-1", null, 0, 10));
        }

        @Test
        void rejectsQuantityOver99() {
            assertThrows(IllegalArgumentException.class, () ->
                    new SearchRequest("user-1", "laptop", "laptop", null, List.of(), Map.of(), "addr-1", null, 100, 10));
        }

        @Test
        void capsLimitAt50() {
            SearchRequest request = new SearchRequest("user-1", "laptop", "laptop", null,
                    List.of(), Map.of(), "addr-1", null, 1, 100);
            assertEquals(50, request.limit());
        }

        @Test
        void defaultsLimitTo10WhenZero() {
            SearchRequest request = new SearchRequest("user-1", "laptop", "laptop", null,
                    List.of(), Map.of(), "addr-1", null, 1, 0);
            assertEquals(10, request.limit());
        }

        @Test
        void defaultsNullCollectionsToEmpty() {
            SearchRequest request = new SearchRequest("user-1", "laptop", "laptop", null,
                    null, null, "addr-1", null, 1, 10);
            assertTrue(request.excludedBrands().isEmpty());
            assertTrue(request.requiredAttributes().isEmpty());
        }
    }

    @Nested
    class QuoteRequestValidation {
        @Test
        void rejectsNullSkuId() {
            assertThrows(NullPointerException.class, () -> new QuoteRequest(null, 1, "user-1", "addr-1"));
        }

        @Test
        void rejectsNullUserId() {
            assertThrows(NullPointerException.class, () -> new QuoteRequest("sku-1", 1, null, "addr-1"));
        }

        @Test
        void rejectsNullAddressId() {
            assertThrows(NullPointerException.class, () -> new QuoteRequest("sku-1", 1, "user-1", null));
        }

        @Test
        void rejectsZeroQuantity() {
            assertThrows(IllegalArgumentException.class, () -> new QuoteRequest("sku-1", 0, "user-1", "addr-1"));
        }
    }

    @Nested
    class PrepareOrderRequestValidation {
        @Test
        void rejectsNullUserId() {
            assertThrows(NullPointerException.class, () -> new PrepareOrderRequest(null, "sku-1", 1, "addr-1"));
        }

        @Test
        void rejectsQuantityOver99() {
            assertThrows(IllegalArgumentException.class, () ->
                    new PrepareOrderRequest("user-1", "sku-1", 100, "addr-1"));
        }

        @Test
        void convenienceConstructorDefaultsBudgetToNull() {
            PrepareOrderRequest request = new PrepareOrderRequest("user-1", "sku-1", 1, "addr-1");
            assertNull(request.budgetMax());
            assertNull(request.budgetMin());
        }

        @Test
        void singleBudgetConstructorSetsMaxOnly() {
            PrepareOrderRequest request = new PrepareOrderRequest("user-1", "sku-1", 1, "addr-1", Money.cny("5000"));
            assertEquals(Money.cny("5000"), request.budgetMax());
            assertNull(request.budgetMin());
        }
    }

    @Nested
    class RegisterAddressCommandValidation {
        @Test
        void rejectsNullUserId() {
            assertThrows(NullPointerException.class, () -> new RegisterAddressCommand(null, "zone-1"));
        }

        @Test
        void rejectsBlankZoneCode() {
            assertThrows(IllegalArgumentException.class, () -> new RegisterAddressCommand("user-1", "  "));
        }
    }

    @Nested
    class EffectContextValidation {
        @Test
        void rejectsNullEffectId() {
            assertThrows(NullPointerException.class, () ->
                    new EffectContext(null, "key", "run-1", "node-1", 0, "user-1", "trace-1"));
        }

        @Test
        void rejectsBlankRunId() {
            assertThrows(IllegalArgumentException.class, () ->
                    new EffectContext("eff-1", "key", "  ", "node-1", 0, "user-1", "trace-1"));
        }

        @Test
        void rejectsNegativeAttempt() {
            assertThrows(IllegalArgumentException.class, () ->
                    new EffectContext("eff-1", "key", "run-1", "node-1", -1, "user-1", "trace-1"));
        }

        @Test
        void acceptsValidContext() {
            EffectContext ctx = new EffectContext("eff-1", "key", "run-1", "node-1", 0, "user-1", "trace-1");
            assertEquals("eff-1", ctx.effectId());
            assertEquals(0, ctx.attempt());
        }
    }

    @Nested
    class SearchResultDefaults {
        @Test
        void defaultsNullCandidatesToEmpty() {
            SearchResult result = new SearchResult(null, Instant.now());
            assertTrue(result.candidates().isEmpty());
        }

        @Test
        void defaultsNullTimestampToNow() {
            Instant before = Instant.now();
            SearchResult result = new SearchResult(List.of(), null);
            Instant after = Instant.now();

            assertFalse(result.observedAt().isBefore(before));
            assertFalse(result.observedAt().isAfter(after));
        }
    }
}
