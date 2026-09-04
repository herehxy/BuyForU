package com.buyforu.agent.application;

import com.buyforu.agent.domain.PlanSpec;
import com.buyforu.commerce.port.model.CommerceModels.Money;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 覆盖 SpringAiPlanningModel 的纯逻辑静态方法，不需要 DeepSeek API。
 * 测试预算方向纠正、品类推断和约束放宽合并规则。
 */
class SpringAiPlanningModelLogicTest {

    // ===== correctBudgetDirection =====

    @Nested
    class CorrectBudgetDirection {
        @Test
        void floorKeywordMovesAmountFromMaxToMin() {
            PlanSpec plan = planWith(null, Money.cny("7000"));
            PlanSpec corrected = SpringAiPlanningModel.correctBudgetDirection(plan, "7000 元以上的笔记本");

            assertNull(corrected.normalizedConstraints().budgetMax());
            assertNotNull(corrected.normalizedConstraints().budgetMin());
            assertEquals(Money.cny("7000"), corrected.normalizedConstraints().budgetMin());
        }

        @Test
        void ceilingKeywordMovesAmountFromMinToMax() {
            PlanSpec plan = planWith(Money.cny("5000"), null);
            // 这里 model 错误地把金额放在了 min，但用户说 "5000以内"
            // 实际 correctBudgetDirection 只在 max==null && min!=null 且 CEILING 时把 min→max
            PlanSpec planReversed = planWith(null, Money.cny("5000"));
            PlanSpec corrected = SpringAiPlanningModel.correctBudgetDirection(planReversed, "5000 元以内");

            assertNotNull(corrected.normalizedConstraints().budgetMax());
            assertNull(corrected.normalizedConstraints().budgetMin());
            assertEquals(Money.cny("5000"), corrected.normalizedConstraints().budgetMax());
        }

        @Test
        void noCorrectionWhenBudgetAlreadyCorrect() {
            PlanSpec plan = planWith(Money.cny("5000"), null);
            PlanSpec corrected = SpringAiPlanningModel.correctBudgetDirection(plan, "5000 元以内");

            assertSame(plan, corrected);
        }

        @Test
        void noCorrectionWhenNoBudgetKeyword() {
            PlanSpec plan = planWith(Money.cny("5000"), null);
            PlanSpec corrected = SpringAiPlanningModel.correctBudgetDirection(plan, "笔记本");

            assertSame(plan, corrected);
        }

        @Test
        void bothFloorAndCeilingKeywordsCancelOut() {
            PlanSpec plan = planWith(Money.cny("5000"), null);
            PlanSpec corrected = SpringAiPlanningModel.correctBudgetDirection(plan, "5000以上但不超过8000");

            assertSame(plan, corrected);
        }

        @Test
        void noCorrectionWhenBothBudgetsPresent() {
            PlanSpec plan = planWith(Money.cny("8000"), Money.cny("3000"));
            PlanSpec corrected = SpringAiPlanningModel.correctBudgetDirection(plan, "3000以上8000以内");

            assertSame(plan, corrected);
        }

        @Test
        void noCorrectionWhenNoBudgetAtAll() {
            PlanSpec plan = planWith(null, null);
            PlanSpec corrected = SpringAiPlanningModel.correctBudgetDirection(plan, "笔记本");

            assertSame(plan, corrected);
        }

        @Test
        void atLeastKeywordTriggersFloor() {
            PlanSpec plan = planWith(null, Money.cny("6000"));
            PlanSpec corrected = SpringAiPlanningModel.correctBudgetDirection(plan, "至少6000元的笔记本");

            assertNull(corrected.normalizedConstraints().budgetMax());
            assertEquals(Money.cny("6000"), corrected.normalizedConstraints().budgetMin());
        }

        @Test
        void buQiKeywordTriggersFloor() {
            PlanSpec plan = planWith(null, Money.cny("5000"));
            PlanSpec corrected = SpringAiPlanningModel.correctBudgetDirection(plan, "不低于5000元");

            assertNull(corrected.normalizedConstraints().budgetMax());
            assertEquals(Money.cny("5000"), corrected.normalizedConstraints().budgetMin());
        }

        @Test
        void buChaoGuoKeywordTriggersCeiling() {
            PlanSpec plan = planWith(null, Money.cny("4000"));
            PlanSpec corrected = SpringAiPlanningModel.correctBudgetDirection(plan, "不超过4000元");

            assertEquals(Money.cny("4000"), corrected.normalizedConstraints().budgetMax());
            assertNull(corrected.normalizedConstraints().budgetMin());
        }
    }

    // ===== fillInferredCategory =====

    @Nested
    class FillInferredCategory {
        @Test
        void infersLaptopFromChineseKeywords() {
            PlanSpec plan = planWithCategory(null, "笔记本");
            PlanSpec filled = SpringAiPlanningModel.fillInferredCategory(plan, "想买个笔记本");

            assertEquals("laptop", filled.normalizedConstraints().category());
        }

        @Test
        void infersLaptopFromQingBoBen() {
            PlanSpec plan = planWithCategory(null, "轻薄本");
            PlanSpec filled = SpringAiPlanningModel.fillInferredCategory(plan, "轻薄本");

            assertEquals("laptop", filled.normalizedConstraints().category());
        }

        @Test
        void infersPhoneFromChineseKeyword() {
            PlanSpec plan = planWithCategory(null, "手机");
            PlanSpec filled = SpringAiPlanningModel.fillInferredCategory(plan, "想买个手机");

            assertEquals("phone", filled.normalizedConstraints().category());
        }

        @Test
        void infersHeadphoneFromChineseKeyword() {
            PlanSpec plan = planWithCategory(null, "耳机");
            PlanSpec filled = SpringAiPlanningModel.fillInferredCategory(plan, "想要一个耳机");

            assertEquals("headphone", filled.normalizedConstraints().category());
        }

        @Test
        void preservesExistingCategoryWhenNoMatch() {
            PlanSpec plan = planWithCategory("tablet", "something unknown");
            PlanSpec filled = SpringAiPlanningModel.fillInferredCategory(plan, "something unknown");

            assertEquals("tablet", filled.normalizedConstraints().category());
        }

        @Test
        void returnsUnchangedPlanWhenCategoryMatches() {
            PlanSpec plan = planWithCategory("laptop", "笔记本");
            PlanSpec filled = SpringAiPlanningModel.fillInferredCategory(plan, "笔记本");

            assertSame(plan, filled);
        }

        @Test
        void addsSearchProductsTaskWhenCategoryFilledAndNotClarification() {
            PlanSpec plan = new PlanSpec(PlanSpec.IntentType.PRODUCT_DISCOVERY,
                    constraintsWithCategory(null, "笔记本"),
                    new PlanSpec.Clarification(false, List.of(), null),
                    PlanSpec.SearchStrategy.HYBRID, List.of(), List.of(),
                    PlanSpec.FallbackPolicy.safeDefault(), "test");
            PlanSpec filled = SpringAiPlanningModel.fillInferredCategory(plan, "笔记本");

            assertTrue(filled.readTasks().contains(PlanSpec.ReadTask.SEARCH_PRODUCTS));
        }
    }

    // ===== applyApprovedRelaxation =====

    @Nested
    class ApplyApprovedRelaxation {
        @Test
        void onlyApprovedFieldsChangeFromProposed() {
            PlanSpec.ShoppingConstraints current = constraintsWith(Money.cny("5000"), "BrandA");
            PlanSpec proposed = new PlanSpec(PlanSpec.IntentType.PRODUCT_DISCOVERY,
                    constraintsWith(Money.cny("8000"), "BrandB"),
                    new PlanSpec.Clarification(false, List.of(), null),
                    PlanSpec.SearchStrategy.HYBRID,
                    List.of(PlanSpec.ReadTask.SEARCH_PRODUCTS), List.of(),
                    PlanSpec.FallbackPolicy.safeDefault(), "relaxed");

            PlanSpec result = SpringAiPlanningModel.applyApprovedRelaxation(
                    proposed, current, List.of("budgetMax"), "original request");

            // 预算应来自 proposed，品牌应保持 current
            assertEquals(Money.cny("8000"), result.normalizedConstraints().budgetMax());
            assertEquals(List.of("BrandA"), result.normalizedConstraints().preferredBrands());
        }

        @Test
        void rejectsAddressIdRelaxation() {
            PlanSpec proposed = new PlanSpec(null, null, null, null, null, null, null, null);
            PlanSpec.ShoppingConstraints current = constraints();

            assertThrows(IllegalArgumentException.class, () ->
                    SpringAiPlanningModel.applyApprovedRelaxation(
                            proposed, current, List.of("addressId"), "request"));
        }

        @Test
        void rejectsEmptyFieldList() {
            PlanSpec proposed = new PlanSpec(null, null, null, null, null, null, null, null);
            PlanSpec.ShoppingConstraints current = constraints();

            assertThrows(IllegalArgumentException.class, () ->
                    SpringAiPlanningModel.applyApprovedRelaxation(
                            proposed, current, List.of(), "request"));
        }

        @Test
        void addressIdAlwaysPreservedFromCurrent() {
            PlanSpec.ShoppingConstraints current = constraintsWith(Money.cny("5000"), "BrandA");
            PlanSpec proposed = new PlanSpec(PlanSpec.IntentType.PRODUCT_DISCOVERY,
                    new PlanSpec.ShoppingConstraints("query", "laptop", Money.cny("8000"), null,
                            List.of("BrandB"), List.of(), Map.of(), 2, "different-addr", null, 1),
                    new PlanSpec.Clarification(false, List.of(), null),
                    PlanSpec.SearchStrategy.HYBRID,
                    List.of(PlanSpec.ReadTask.SEARCH_PRODUCTS), List.of(),
                    PlanSpec.FallbackPolicy.safeDefault(), "relaxed");

            PlanSpec result = SpringAiPlanningModel.applyApprovedRelaxation(
                    proposed, current, List.of("budgetMax", "preferredBrands"), "request");

            assertEquals("addr-1", result.normalizedConstraints().addressId());
        }

        @Test
        void incrementsVersionFromCurrent() {
            PlanSpec.ShoppingConstraints current = new PlanSpec.ShoppingConstraints("laptop", "laptop",
                    Money.cny("5000"), null, List.of(), List.of(), Map.of(), 1, "addr-1", null, 3);
            PlanSpec proposed = new PlanSpec(PlanSpec.IntentType.PRODUCT_DISCOVERY,
                    new PlanSpec.ShoppingConstraints("laptop", "laptop", Money.cny("8000"), null,
                            List.of(), List.of(), Map.of(), 1, "addr-1", null, 1),
                    new PlanSpec.Clarification(false, List.of(), null),
                    PlanSpec.SearchStrategy.HYBRID,
                    List.of(PlanSpec.ReadTask.SEARCH_PRODUCTS), List.of(),
                    PlanSpec.FallbackPolicy.safeDefault(), "relaxed");

            PlanSpec result = SpringAiPlanningModel.applyApprovedRelaxation(
                    proposed, current, List.of("budgetMax"), "request");

            assertEquals(4, result.normalizedConstraints().version());
        }

        @Test
        void ensuresSearchProductsTaskPresent() {
            PlanSpec.ShoppingConstraints current = constraints();
            PlanSpec proposed = new PlanSpec(PlanSpec.IntentType.PRODUCT_DISCOVERY,
                    constraints(),
                    new PlanSpec.Clarification(false, List.of(), null),
                    PlanSpec.SearchStrategy.HYBRID,
                    List.of(PlanSpec.ReadTask.CALCULATE_QUOTES), List.of(),
                    PlanSpec.FallbackPolicy.safeDefault(), "relaxed");

            PlanSpec result = SpringAiPlanningModel.applyApprovedRelaxation(
                    proposed, current, List.of("budgetMax"), "request");

            assertTrue(result.readTasks().contains(PlanSpec.ReadTask.SEARCH_PRODUCTS));
        }
    }

    // ===== helpers =====

    private static PlanSpec planWith(Money budgetMax, Money budgetMin) {
        PlanSpec.ShoppingConstraints constraints = new PlanSpec.ShoppingConstraints("laptop", "laptop",
                budgetMax, budgetMin, List.of(), List.of(), Map.of(), 1, "addr-1", null, 1);
        return new PlanSpec(PlanSpec.IntentType.PRODUCT_DISCOVERY, constraints,
                new PlanSpec.Clarification(false, List.of(), null),
                PlanSpec.SearchStrategy.HYBRID,
                List.of(PlanSpec.ReadTask.SEARCH_PRODUCTS), List.of(),
                PlanSpec.FallbackPolicy.safeDefault(), "test");
    }

    private static PlanSpec planWithCategory(String category, String query) {
        PlanSpec.ShoppingConstraints constraints = new PlanSpec.ShoppingConstraints(query, category,
                null, null, List.of(), List.of(), Map.of(), 1, "addr-1", null, 1);
        return new PlanSpec(PlanSpec.IntentType.PRODUCT_DISCOVERY, constraints,
                new PlanSpec.Clarification(false, List.of(), null),
                PlanSpec.SearchStrategy.HYBRID,
                List.of(PlanSpec.ReadTask.SEARCH_PRODUCTS), List.of(),
                PlanSpec.FallbackPolicy.safeDefault(), "test");
    }

    private static PlanSpec.ShoppingConstraints constraintsWithCategory(String category, String query) {
        return new PlanSpec.ShoppingConstraints(query, category,
                null, null, List.of(), List.of(), Map.of(), 1, "addr-1", null, 1);
    }

    private static PlanSpec.ShoppingConstraints constraintsWith(Money budgetMax, String preferredBrand) {
        return new PlanSpec.ShoppingConstraints("laptop", "laptop",
                budgetMax, null, List.of(preferredBrand), List.of(), Map.of(), 1, "addr-1", null, 1);
    }

    private static PlanSpec.ShoppingConstraints constraints() {
        return new PlanSpec.ShoppingConstraints("laptop", "laptop",
                Money.cny("5000"), null, List.of("BrandA"), List.of(), Map.of(), 1, "addr-1",
                LocalDate.now().plusDays(7), 1);
    }
}
