package com.buyforu.agent.application;

import com.buyforu.agent.domain.PlanSpec;

import java.util.List;

/** 测试专用的确定性规划器；生产环境始终使用 SpringAiPlanningModel，不会装配此类。 */
public final class DeterministicPlanningModel implements PlanningModel {
    @Override
    public PlanSpec replan(String request, PlanSpec.ShoppingConstraints constraints, String failureReason, int attempt) {
        PlanSpec plan = createPlan(request, constraints);
        var c = plan.normalizedConstraints();
        return new PlanSpec(plan.intentType(), new PlanSpec.ShoppingConstraints(c.query(), c.category(), c.budgetMax(),
                c.preferredBrands(), c.excludedBrands(), c.requiredAttributes(), c.quantity(), c.addressId(),
                c.deliveryBy(), c.version() + 1), plan.clarification(), PlanSpec.SearchStrategy.HYBRID,
                plan.readTasks(), plan.rankingPreferences(), plan.fallbackPolicy(), "search replan " + attempt);
    }

    @Override
    public PlanSpec relaxConstraints(String request, PlanSpec.ShoppingConstraints constraints, String instruction) {
        PlanSpec base = createPlan(request, constraints);
        var c = base.normalizedConstraints();
        return new PlanSpec(base.intentType(), new PlanSpec.ShoppingConstraints(c.query(), c.category(), null,
                c.preferredBrands(), c.excludedBrands(), c.requiredAttributes(), c.quantity(), c.addressId(),
                c.deliveryBy(), c.version() + 1), base.clarification(), base.searchStrategy(), base.readTasks(),
                base.rankingPreferences(), base.fallbackPolicy(), "explicit relaxation");
    }

    @Override
    public PlanSpec createPlan(String request, PlanSpec.ShoppingConstraints constraints) {
        // 用最小确定性规则制造可执行/需澄清计划，让工作流测试不消耗真实 DeepSeek API。
        boolean missingCategory = constraints.category() == null || constraints.category().isBlank();
        boolean missingAddress = constraints.addressId() == null || constraints.addressId().isBlank();
        boolean clarification = missingCategory || missingAddress;
        List<String> missing = new java.util.ArrayList<>();
        if (missingCategory) missing.add("category");
        if (missingAddress) missing.add("addressId");
        return new PlanSpec(PlanSpec.IntentType.PRODUCT_DISCOVERY, constraints,
                new PlanSpec.Clarification(clarification, missing,
                        clarification ? "请补充商品品类和收货地址。" : null),
                PlanSpec.SearchStrategy.HYBRID,
                clarification ? List.of() : List.of(PlanSpec.ReadTask.SEARCH_PRODUCTS,
                        PlanSpec.ReadTask.CALCULATE_QUOTES,
                        PlanSpec.ReadTask.CHECK_INVENTORY_DELIVERY),
                List.of(PlanSpec.RankingPreference.SPEC_MATCH, PlanSpec.RankingPreference.PRICE,
                        PlanSpec.RankingPreference.DELIVERY), PlanSpec.FallbackPolicy.safeDefault(), "test fixture");
    }
}
