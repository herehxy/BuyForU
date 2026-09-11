package com.buyforu.agent.application;

import com.buyforu.agent.domain.PlanSpec;
import com.buyforu.agent.domain.PlanSpecValidator;
import com.buyforu.commerce.port.model.CommerceModels.Money;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class SpringAiPlanningModelTest {
    @Test
    void infersLaptopCategorySoClarificationIsNotStuck() {
        PlanSpec asked = new PlanSpec(PlanSpec.IntentType.PRODUCT_DISCOVERY,
                new PlanSpec.ShoppingConstraints("轻薄本 16GB", null, Money.cny("5000"), null,
                        List.of(), List.of(), Map.of("type", "轻薄本"), 1, "address-1", null, 1),
                new PlanSpec.Clarification(true, List.of("category"), "请问要哪种笔记本？"),
                PlanSpec.SearchStrategy.HYBRID, List.of(),
                List.of(PlanSpec.RankingPreference.PRICE), PlanSpec.FallbackPolicy.safeDefault(), "ask");

        PlanSpec filled = SpringAiPlanningModel.fillInferredCategory(asked, "帮我找一台轻薄本");

        assertEquals("laptop", filled.normalizedConstraints().category());
        assertFalse(filled.clarification().required());
        assertTrue(filled.readTasks().contains(PlanSpec.ReadTask.SEARCH_PRODUCTS));
    }

    @Test
    void approvedRelaxationKeepsExistingExecutionContextAndRemainsSearchable() {
        PlanSpec.ShoppingConstraints current = new PlanSpec.ShoppingConstraints(
                "16GB 轻薄本", "laptop", Money.cny("5000"), null, List.of("Aurora"), List.of(),
                Map.of("memory", "16GB"), 1, "address-1", LocalDate.of(2026, 8, 15), 3);
        // 模拟模型正确理解了新预算，却错误地忘记地址并再次要求澄清，同时漏掉搜索任务。
        PlanSpec modelProposal = new PlanSpec(PlanSpec.IntentType.PRODUCT_DISCOVERY,
                new PlanSpec.ShoppingConstraints("16GB 轻薄本", "laptop", Money.cny("5500"), null,
                        List.of(), List.of(), Map.of(), 1, null, null, 1),
                new PlanSpec.Clarification(true, List.of("addressId"), "请提供地址"),
                PlanSpec.SearchStrategy.HYBRID, List.of(), List.of(PlanSpec.RankingPreference.PRICE),
                PlanSpec.FallbackPolicy.safeDefault(), "raise budget");

        PlanSpec relaxed = SpringAiPlanningModel.applyApprovedRelaxation(
                modelProposal, current, List.of("budgetMax"), "帮我找一台轻薄本");

        assertEquals(Money.cny("5500"), relaxed.normalizedConstraints().budgetMax());
        assertEquals("address-1", relaxed.normalizedConstraints().addressId());
        assertEquals("laptop", relaxed.normalizedConstraints().category());
        assertEquals(4, relaxed.normalizedConstraints().version());
        assertFalse(relaxed.clarification().required());
        assertTrue(relaxed.readTasks().contains(PlanSpec.ReadTask.SEARCH_PRODUCTS));
        assertDoesNotThrow(() -> new PlanSpecValidator().validate(relaxed));
    }

    @Test
    void movesCeilingBudgetToFloorWhenUserSaidAbove() {
        PlanSpec inverted = new PlanSpec(PlanSpec.IntentType.PRODUCT_DISCOVERY,
                new PlanSpec.ShoppingConstraints("轻薄本", "laptop", Money.cny("7000"), null,
                        List.of(), List.of(), Map.of(), 1, "address-1", null, 1),
                new PlanSpec.Clarification(false, List.of(), null),
                PlanSpec.SearchStrategy.HYBRID, List.of(PlanSpec.ReadTask.SEARCH_PRODUCTS),
                List.of(), PlanSpec.FallbackPolicy.safeDefault(), "inverted");

        PlanSpec fixed = SpringAiPlanningModel.correctBudgetDirection(inverted, "7000元以上、16GB 内存的轻薄本");

        assertNull(fixed.normalizedConstraints().budgetMax());
        assertEquals(Money.cny("7000"), fixed.normalizedConstraints().budgetMin());
    }

    @Test
    void fillInferredCategoryAcceptsMissingClarification() {
        PlanSpec raw = new PlanSpec(PlanSpec.IntentType.PRODUCT_DISCOVERY,
                new PlanSpec.ShoppingConstraints("轻薄本", null, null, null,
                        List.of(), List.of(), Map.of(), 1, "address-1", null, 1),
                null, null, List.of(), List.of(), null, "raw");
        PlanSpec filled = SpringAiPlanningModel.fillInferredCategory(raw, "帮我找一台轻薄本");
        assertEquals("laptop", filled.normalizedConstraints().category());
        assertFalse(filled.clarification().required());
    }

    @Test
    void unrelatedChineseCharactersDoNotBecomeBudgetOrLaptopSignals() {
        PlanSpec raw = new PlanSpec(PlanSpec.IntentType.PRODUCT_DISCOVERY,
                new PlanSpec.ShoppingConstraints("", null, Money.cny("100"), null,
                        List.of(), List.of(), Map.of(), 1, "address-1", null, 1),
                new PlanSpec.Clarification(true, List.of("category"), "品类？"),
                PlanSpec.SearchStrategy.HYBRID, List.of(), List.of(), PlanSpec.FallbackPolicy.safeDefault(), "raw");

        PlanSpec budget = SpringAiPlanningModel.correctBudgetDirection(raw, "我起床后想买这本书，预算100元以内");
        PlanSpec category = SpringAiPlanningModel.fillInferredCategory(budget, "我想买这本书");

        assertEquals(Money.cny("100"), category.normalizedConstraints().budgetMax());
        assertNull(category.normalizedConstraints().budgetMin());
        assertNull(category.normalizedConstraints().category());
        assertTrue(category.clarification().required());
    }
}
