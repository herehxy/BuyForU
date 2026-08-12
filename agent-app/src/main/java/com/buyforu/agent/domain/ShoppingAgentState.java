package com.buyforu.agent.domain;

import com.buyforu.commerce.port.model.CommerceModels.ConfirmableOrderSnapshot;
import com.buyforu.commerce.port.model.CommerceModels.Order;
import com.buyforu.commerce.port.model.CommerceModels.ProductCandidate;

import java.time.Instant;
import java.util.List;

/**
 * 一次购物任务的持久化业务状态。
 *
 * <p>它与 LangGraph checkpoint 不同：本状态回答“业务现在是什么结果”，checkpoint 回答
 * “图从哪个节点继续”。两者共同用于进程重启和副作用后的恢复。</p>
 */
public record ShoppingAgentState(
        String runId,
        String conversationId,
        String userId,
        String traceId,
        String originalRequest,
        PlanSpec planSpec,
        Phase phase,
        List<ProductCandidate> candidateSet,
        int selectedCandidateIndex,
        ConfirmableOrderSnapshot confirmableSnapshot,
        PendingApproval pendingApproval,
        ActiveEffect activeEffect,
        int candidateFallbackCount,
        int searchReplanCount,
        long planVersion,
        String lastError,
        Order finalOrder,
        Instant updatedAt
) {
    public ShoppingAgentState {
        candidateSet = candidateSet == null ? List.of() : List.copyOf(candidateSet);
    }

    /** 状态转换只能由固定图和应用服务完成，客户端不能直接提交目标状态。 */
    public enum Phase {
        NEW,
        NEEDS_CLARIFICATION,
        SEARCHING,
        PRESENTING_CANDIDATES,
        PREPARING_CONFIRMABLE_ORDER,
        WAITING_APPROVAL,
        CREATING_ORDER,
        NEEDS_CONSTRAINT_RELAXATION,
        COMPLETED,
        CANCELLED,
        FAILED
    }

    public record PendingApproval(String approvalRequestId, String snapshotId, String expectedSummaryHash,
                                  Instant expiresAt) {
    }

    public record ActiveEffect(String effectId, String operation, String requestHash, EffectStatus status) {
    }

    public enum EffectStatus { PENDING_EFFECT, EFFECT_APPLIED }
}
