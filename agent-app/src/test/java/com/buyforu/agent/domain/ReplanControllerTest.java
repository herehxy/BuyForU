package com.buyforu.agent.domain;

import com.buyforu.commerce.port.model.CommerceModels.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 覆盖三级失败策略的每条分支：候选降级、搜索重试和约束放宽。
 */
class ReplanControllerTest {
    private final ReplanController controller = new ReplanController();

    @Test
    void fallsBackToNextCandidateWhenPolicyAllowsAndCandidatesRemain() {
        ShoppingAgentState state = state(0, 0, fallbackPolicy(true, 2),
                List.of(candidate("sku-1"), candidate("sku-2"), candidate("sku-3")));

        ReplanController.ReplanDecision decision = controller.decide(state);

        assertEquals(ReplanController.ReplanLevel.CANDIDATE_FALLBACK, decision.level());
        assertEquals(1, decision.candidateIndex());
    }

    @Test
    void skipsCandidateFallbackWhenNoMoreCandidates() {
        ShoppingAgentState state = state(2, 0, fallbackPolicy(true, 2),
                List.of(candidate("sku-1"), candidate("sku-2"), candidate("sku-3")));

        ReplanController.ReplanDecision decision = controller.decide(state);

        assertEquals(ReplanController.ReplanLevel.SEARCH_REPLAN, decision.level());
        assertEquals(-1, decision.candidateIndex());
    }

    @Test
    void skipsCandidateFallbackWhenPolicyDisabled() {
        ShoppingAgentState state = state(0, 0, fallbackPolicy(false, 2),
                List.of(candidate("sku-1"), candidate("sku-2")));

        ReplanController.ReplanDecision decision = controller.decide(state);

        assertEquals(ReplanController.ReplanLevel.SEARCH_REPLAN, decision.level());
    }

    @Test
    void fallsBackToSearchReplanWhenWithinMaxReplans() {
        ShoppingAgentState state = state(0, 1, fallbackPolicy(true, 2),
                List.of(candidate("sku-1")));

        ReplanController.ReplanDecision decision = controller.decide(state);

        assertEquals(ReplanController.ReplanLevel.SEARCH_REPLAN, decision.level());
    }

    @Test
    void escalatesToConstraintRelaxationWhenSearchReplansExhausted() {
        ShoppingAgentState state = state(0, 2, fallbackPolicy(true, 2),
                List.of(candidate("sku-1")));

        ReplanController.ReplanDecision decision = controller.decide(state);

        assertEquals(ReplanController.ReplanLevel.CONSTRAINT_RELAXATION, decision.level());
        assertEquals(-1, decision.candidateIndex());
    }

    @Test
    void escalatesToConstraintRelaxationImmediatelyWhenZeroReplansAllowed() {
        ShoppingAgentState state = state(0, 0, fallbackPolicy(false, 0),
                List.of(candidate("sku-1")));

        ReplanController.ReplanDecision decision = controller.decide(state);

        assertEquals(ReplanController.ReplanLevel.CONSTRAINT_RELAXATION, decision.level());
    }

    private static ShoppingAgentState state(int selectedIndex, int searchReplanCount,
                                            PlanSpec.FallbackPolicy fallbackPolicy,
                                            List<com.buyforu.commerce.port.model.CommerceModels.ProductCandidate> candidates) {
        PlanSpec planSpec = new PlanSpec(PlanSpec.IntentType.PRODUCT_DISCOVERY,
                new PlanSpec.ShoppingConstraints("laptop", "laptop", Money.cny("5000"), null,
                        List.of(), List.of(), Map.of(), 1, "addr-1", LocalDate.now().plusDays(7), 1),
                new PlanSpec.Clarification(false, List.of(), null),
                PlanSpec.SearchStrategy.HYBRID,
                List.of(PlanSpec.ReadTask.SEARCH_PRODUCTS), List.of(),
                fallbackPolicy, "test");
        return new ShoppingAgentState("run-1", "conv-1", "user-1", "trace-1", "buy laptop",
                planSpec, ShoppingAgentState.Phase.SEARCHING, candidates, selectedIndex,
                null, null, null, 0, searchReplanCount, 1, null, null, Instant.now());
    }

    private static com.buyforu.commerce.port.model.CommerceModels.ProductCandidate candidate(String skuId) {
        return new com.buyforu.commerce.port.model.CommerceModels.ProductCandidate(
                "prod-" + skuId, skuId, "Product " + skuId, "Brand", Map.of(),
                Money.cny("3000"), true, LocalDate.now().plusDays(3));
    }

    private static PlanSpec.FallbackPolicy fallbackPolicy(boolean candidateFallback, int maxSearchReplans) {
        return new PlanSpec.FallbackPolicy(candidateFallback, maxSearchReplans, true);
    }
}
