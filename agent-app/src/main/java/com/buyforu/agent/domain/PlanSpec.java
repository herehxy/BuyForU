package com.buyforu.agent.domain;

import com.buyforu.commerce.port.model.CommerceModels.Money;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * LLM 唯一允许产生的规划结果。
 *
 * <p>它只描述“约束、读取任务和排序偏好”，不描述任意 DAG；真正的执行拓扑由
 * {@code FixedShoppingGraph} 固定，从而防止模型越权调用写操作或跳过人工确认。</p>
 */
public record PlanSpec(
        IntentType intentType,
        ShoppingConstraints normalizedConstraints,
        Clarification clarification,
        SearchStrategy searchStrategy,
        List<ReadTask> readTasks,
        List<RankingPreference> rankingPreferences,
        FallbackPolicy fallbackPolicy,
        String rationale
) {
    public PlanSpec {
        readTasks = readTasks == null ? List.of() : List.copyOf(readTasks);
        rankingPreferences = rankingPreferences == null ? List.of() : List.copyOf(rankingPreferences);
    }

    public enum IntentType { PRODUCT_DISCOVERY, PRODUCT_COMPARISON, PURCHASE }

    public enum ReadTask {
        SEARCH_PRODUCTS,
        CALCULATE_QUOTES,
        CHECK_INVENTORY_DELIVERY,
        LOOKUP_POLICY
    }

    public enum SearchStrategy { EXACT_FIRST, HYBRID, SEMANTIC_FIRST }

    public enum RankingPreference { PRICE, DELIVERY, SPEC_MATCH, BRAND_PREFERENCE }

    public record Clarification(boolean required, List<String> missingFields, String question) {
        public Clarification {
            missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
        }
    }

    public record FallbackPolicy(boolean candidateFallback, int maxSearchReplans,
                                 boolean requireApprovalForConstraintRelaxation) {
        public static FallbackPolicy safeDefault() {
            return new FallbackPolicy(true, 2, true);
        }
    }

    /** 用户明确表达并经应用合并后的硬约束；version 用于追踪每次受控变更。 */
    public record ShoppingConstraints(
            String query,
            String category,
            Money budgetMax,
            List<String> preferredBrands,
            List<String> excludedBrands,
            Map<String, String> requiredAttributes,
            int quantity,
            String addressId,
            LocalDate deliveryBy,
            long version
    ) {
        public ShoppingConstraints {
            preferredBrands = preferredBrands == null ? List.of() : List.copyOf(preferredBrands);
            excludedBrands = excludedBrands == null ? List.of() : List.copyOf(excludedBrands);
            requiredAttributes = requiredAttributes == null ? Map.of() : Map.copyOf(requiredAttributes);
        }
    }
}
