package com.buyforu.agent.domain;

import com.buyforu.commerce.port.model.CommerceModels.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 PlanSpec 和 ShoppingAgentState 的紧凑构造器对 null 字段的默认填充逻辑。
 */
class PlanSpecDefaultsTest {

    @Test
    void planSpecFillsSafeDefaultsForNullFields() {
        PlanSpec plan = new PlanSpec(null, null, null, null, null, null, null, null);

        assertEquals(PlanSpec.IntentType.PRODUCT_DISCOVERY, plan.intentType());
        assertNotNull(plan.normalizedConstraints());
        assertNotNull(plan.clarification());
        assertFalse(plan.clarification().required());
        assertEquals(PlanSpec.SearchStrategy.HYBRID, plan.searchStrategy());
        assertTrue(plan.readTasks().isEmpty());
        assertTrue(plan.rankingPreferences().isEmpty());
        assertNotNull(plan.fallbackPolicy());
        assertTrue(plan.fallbackPolicy().candidateFallback());
        assertEquals(2, plan.fallbackPolicy().maxSearchReplans());
        assertTrue(plan.fallbackPolicy().requireApprovalForConstraintRelaxation());
    }

    @Test
    void planSpecPreservesExplicitValues() {
        PlanSpec plan = new PlanSpec(PlanSpec.IntentType.PURCHASE,
                constraints(), new PlanSpec.Clarification(true, List.of("category"), "what?"),
                PlanSpec.SearchStrategy.SEMANTIC_FIRST,
                List.of(PlanSpec.ReadTask.SEARCH_PRODUCTS), List.of(PlanSpec.RankingPreference.PRICE),
                new PlanSpec.FallbackPolicy(false, 1, true), "explicit");

        assertEquals(PlanSpec.IntentType.PURCHASE, plan.intentType());
        assertEquals(PlanSpec.SearchStrategy.SEMANTIC_FIRST, plan.searchStrategy());
        assertFalse(plan.fallbackPolicy().candidateFallback());
        assertEquals(1, plan.fallbackPolicy().maxSearchReplans());
    }

    @Test
    void readTasksAndRankingPreferencesAreDefensivelyCopied() {
        var tasks = new java.util.ArrayList<>(List.of(PlanSpec.ReadTask.SEARCH_PRODUCTS));
        var prefs = new java.util.ArrayList<>(List.of(PlanSpec.RankingPreference.PRICE));
        PlanSpec plan = new PlanSpec(PlanSpec.IntentType.PRODUCT_DISCOVERY, constraints(),
                null, null, tasks, prefs, null, "test");

        tasks.add(PlanSpec.ReadTask.CALCULATE_QUOTES);
        prefs.add(PlanSpec.RankingPreference.DELIVERY);

        assertEquals(1, plan.readTasks().size());
        assertEquals(1, plan.rankingPreferences().size());
    }

    @Test
    void shoppingConstraintsDefaultsNullCollections() {
        PlanSpec.ShoppingConstraints constraints = new PlanSpec.ShoppingConstraints(
                "laptop", "laptop", null, null, null, null, null, 1, "addr-1", null, 1);

        assertTrue(constraints.preferredBrands().isEmpty());
        assertTrue(constraints.excludedBrands().isEmpty());
        assertTrue(constraints.requiredAttributes().isEmpty());
    }

    @Test
    void shoppingAgentStateDefaultsNullCandidateSet() {
        ShoppingAgentState state = new ShoppingAgentState("run-1", "conv-1", "user-1", "trace-1",
                "request", null, ShoppingAgentState.Phase.NEW, null, 0,
                null, null, null, 0, 0, 1, null, null, Instant.now());

        assertNotNull(state.candidateSet());
        assertTrue(state.candidateSet().isEmpty());
    }

    @Test
    void shoppingAgentStateCandidateSetIsDefensivelyCopied() {
        var candidates = new java.util.ArrayList<>(List.of(
                new com.buyforu.commerce.port.model.CommerceModels.ProductCandidate(
                        "p1", "sku-1", "Laptop", "Brand", Map.of(),
                        Money.cny("3000"), true, LocalDate.now().plusDays(3))));
        ShoppingAgentState state = new ShoppingAgentState("run-1", "conv-1", "user-1", "trace-1",
                "request", null, ShoppingAgentState.Phase.NEW, candidates, 0,
                null, null, null, 0, 0, 1, null, null, Instant.now());

        candidates.add(new com.buyforu.commerce.port.model.CommerceModels.ProductCandidate(
                "p2", "sku-2", "Phone", "Brand", Map.of(),
                Money.cny("5000"), true, LocalDate.now().plusDays(3)));

        assertEquals(1, state.candidateSet().size());
    }

    @Test
    void clarificationDefaultsNullMissingFields() {
        PlanSpec.Clarification clarification = new PlanSpec.Clarification(false, null, null);
        assertTrue(clarification.missingFields().isEmpty());
    }

    @Test
    void fallbackPolicySafeDefaultHasExpectedValues() {
        PlanSpec.FallbackPolicy policy = PlanSpec.FallbackPolicy.safeDefault();

        assertTrue(policy.candidateFallback());
        assertEquals(2, policy.maxSearchReplans());
        assertTrue(policy.requireApprovalForConstraintRelaxation());
    }

    private PlanSpec.ShoppingConstraints constraints() {
        return new PlanSpec.ShoppingConstraints("laptop", "laptop", Money.cny("5000"), null,
                List.of(), List.of(), Map.of(), 1, "addr-1", LocalDate.now().plusDays(1), 1);
    }
}
