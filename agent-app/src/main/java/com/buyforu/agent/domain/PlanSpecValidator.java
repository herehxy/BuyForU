package com.buyforu.agent.domain;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ArrayList;

/**
 * 结构化输出之后的第二道业务校验。
 * JSON Schema 只能保证形状正确；这里继续拒绝越权任务、矛盾品牌、非法数量和不可执行计划。
 */
public final class PlanSpecValidator {
    private static final int MAX_READ_TASKS = 4;
    private static final int MAX_LIST_ITEMS = 20;
    private static final EnumSet<PlanSpec.ReadTask> ALLOWED = EnumSet.allOf(PlanSpec.ReadTask.class);
    private static final java.util.Set<String> CLARIFIABLE_FIELDS = java.util.Set.of(
            "query", "category", "budgetMax", "budgetMin", "preferredBrands", "excludedBrands",
            "requiredAttributes", "quantity", "addressId", "deliveryBy");

    public PlanSpec validate(PlanSpec plan) {
        if (plan == null) throw new IllegalArgumentException("plan is required");
        if (plan.intentType() == null) throw new IllegalArgumentException("intentType is required");
        if (plan.normalizedConstraints() == null) throw new IllegalArgumentException("constraints are required");
        if (plan.clarification() == null) throw new IllegalArgumentException("clarification is required");
        if (plan.searchStrategy() == null) throw new IllegalArgumentException("searchStrategy is required");
        if (plan.rankingPreferences() == null) throw new IllegalArgumentException("rankingPreferences are required");
        if (plan.readTasks().size() > MAX_READ_TASKS) {
            throw new IllegalArgumentException("at most four read tasks are allowed");
        }
        if (!ALLOWED.containsAll(plan.readTasks())) {
            throw new IllegalArgumentException("plan contains an unknown task");
        }
        if (new LinkedHashSet<>(plan.readTasks()).size() != plan.readTasks().size()) {
            throw new IllegalArgumentException("duplicate read tasks are not allowed");
        }
        if (new LinkedHashSet<>(plan.rankingPreferences()).size() != plan.rankingPreferences().size()) {
            throw new IllegalArgumentException("duplicate ranking preferences are not allowed");
        }
        if (plan.rankingPreferences().size() > PlanSpec.RankingPreference.values().length) {
            throw new IllegalArgumentException("too many ranking preferences");
        }

        List<PlanSpec.ReadTask> tasks = plan.readTasks();
        if (!plan.clarification().required() && !tasks.contains(PlanSpec.ReadTask.SEARCH_PRODUCTS)) {
            throw new IllegalArgumentException("non-clarification plans must search products");
        }
        if (plan.fallbackPolicy() == null || plan.fallbackPolicy().maxSearchReplans() < 0
                || plan.fallbackPolicy().maxSearchReplans() > 2) {
            throw new IllegalArgumentException("maxSearchReplans must be between zero and two");
        }
        if (!plan.fallbackPolicy().requireApprovalForConstraintRelaxation()) {
            throw new IllegalArgumentException("constraint relaxation always requires user approval");
        }
        if (plan.normalizedConstraints().version() < 1) {
            throw new IllegalArgumentException("constraint version must be positive");
        }
        var budgetMin = plan.normalizedConstraints().budgetMin();
        var budgetMax = plan.normalizedConstraints().budgetMax();
        if (budgetMin != null && budgetMax != null
                && budgetMin.amount().compareTo(budgetMax.amount()) > 0) {
            throw new IllegalArgumentException("budgetMin cannot exceed budgetMax");
        }
        int quantity = plan.normalizedConstraints().quantity();
        if (!plan.clarification().required() && (quantity <= 0 || quantity > 99)) {
            throw new IllegalArgumentException("quantity must be between 1 and 99 for an executable plan");
        }
        if (plan.normalizedConstraints().preferredBrands().size() > MAX_LIST_ITEMS
                || plan.normalizedConstraints().excludedBrands().size() > MAX_LIST_ITEMS
                || plan.normalizedConstraints().requiredAttributes().size() > MAX_LIST_ITEMS) {
            throw new IllegalArgumentException("constraint collections cannot exceed 20 items");
        }
        if (plan.normalizedConstraints().preferredBrands().stream().anyMatch(PlanSpecValidator::blank)
                || plan.normalizedConstraints().excludedBrands().stream().anyMatch(PlanSpecValidator::blank)
                || plan.normalizedConstraints().requiredAttributes().entrySet().stream()
                .anyMatch(entry -> blank(entry.getKey()) || blank(entry.getValue()))) {
            throw new IllegalArgumentException("constraint collections cannot contain blank values");
        }
        boolean brandConflict = plan.normalizedConstraints().preferredBrands().stream()
                .anyMatch(preferred -> plan.normalizedConstraints().excludedBrands().stream()
                        .anyMatch(excluded -> excluded.equalsIgnoreCase(preferred)));
        if (brandConflict) throw new IllegalArgumentException("a brand cannot be both preferred and excluded");
        List<String> actuallyMissing = new ArrayList<>();
        if (blank(plan.normalizedConstraints().category())) actuallyMissing.add("category");
        if (blank(plan.normalizedConstraints().addressId())) actuallyMissing.add("addressId");
        if (!actuallyMissing.isEmpty() && !plan.clarification().required()) {
            throw new IllegalArgumentException("missing category or addressId requires clarification");
        }
        if (plan.clarification().required() && plan.clarification().missingFields().isEmpty()) {
            throw new IllegalArgumentException("clarification must list at least one missing field");
        }
        if (!CLARIFIABLE_FIELDS.containsAll(plan.clarification().missingFields())) {
            throw new IllegalArgumentException("clarification contains an unknown field");
        }
        if (new LinkedHashSet<>(plan.clarification().missingFields()).size()
                != plan.clarification().missingFields().size()) {
            throw new IllegalArgumentException("clarification fields cannot be duplicated");
        }
        if (plan.clarification().required() && blank(plan.clarification().question())) {
            throw new IllegalArgumentException("clarification question is required");
        }
        return plan;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
