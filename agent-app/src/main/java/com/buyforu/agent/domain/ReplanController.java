package com.buyforu.agent.domain;

/**
 * 严格的三级失败策略：先换已验证候选，再在硬约束不变时重新搜索，最后才请求用户放宽约束。
 */
public final class ReplanController {
    public ReplanDecision decide(ShoppingAgentState state) {
        int nextIndex = state.selectedCandidateIndex() + 1;
        if (state.planSpec().fallbackPolicy().candidateFallback()
                && nextIndex < state.candidateSet().size()) {
            return new ReplanDecision(ReplanLevel.CANDIDATE_FALLBACK, nextIndex,
                    "use the next already validated candidate");
        }
        if (state.searchReplanCount() < state.planSpec().fallbackPolicy().maxSearchReplans()) {
            return new ReplanDecision(ReplanLevel.SEARCH_REPLAN, -1,
                    "rerun search without relaxing hard constraints");
        }
        return new ReplanDecision(ReplanLevel.CONSTRAINT_RELAXATION, -1,
                "ask the user before relaxing any hard constraint");
    }

    public enum ReplanLevel { CANDIDATE_FALLBACK, SEARCH_REPLAN, CONSTRAINT_RELAXATION }

    public record ReplanDecision(ReplanLevel level, int candidateIndex, String reason) {
    }
}
