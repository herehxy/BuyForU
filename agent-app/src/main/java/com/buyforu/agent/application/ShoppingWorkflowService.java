package com.buyforu.agent.application;

import com.buyforu.agent.domain.PlanSpec;
import com.buyforu.agent.domain.PlanSpecValidator;
import com.buyforu.agent.domain.ReplanController;
import com.buyforu.agent.domain.ShoppingAgentState;
import com.buyforu.agent.domain.ShoppingAgentState.*;
import com.buyforu.commerce.port.CommerceGateway;
import com.buyforu.commerce.port.CommerceOperationException;
import com.buyforu.commerce.port.model.CommerceModels.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Comparator;
import java.util.Optional;

/**
 * 固定图节点背后的应用服务。
 *
 * <p>本类可以决定何时搜索、何时要求确认以及怎样 Replan，但所有价格、优惠、库存和履约结果
 * 都必须通过 CommerceGateway 获取，不能在 Agent 内计算。</p>
 */
@Service
public class ShoppingWorkflowService {
    private final CommerceGateway commerce;
    private final PlanningModel planningModel;
    private final AgentRunStore store;
    private final PlanSpecValidator planValidator = new PlanSpecValidator();
    private final ReplanController replanController = new ReplanController();
    private final Clock clock;
    private final ConversationMemory memory;

    @Autowired
    public ShoppingWorkflowService(CommerceGateway commerce, PlanningModel planningModel, AgentRunStore store,
                                   ConversationMemory memory) {
        this(commerce, planningModel, store, memory, Clock.systemUTC());
    }

    ShoppingWorkflowService(CommerceGateway commerce, PlanningModel planningModel, AgentRunStore store,
                            ConversationMemory memory, Clock clock) {
        this.commerce = commerce;
        this.planningModel = planningModel;
        this.store = store;
        this.clock = clock;
        this.memory = memory;
    }

    // ===== 规划与任务读取 =====================================================

    public ShoppingAgentState start(String conversationId, String userId, String request,
                                    PlanSpec.ShoppingConstraints constraints) {
        ShoppingAgentState planned = planNewRun(conversationId, userId, request, constraints);
        return planned.phase() == Phase.SEARCHING ? search(planned.runId(), userId) : planned;
    }

    ShoppingAgentState planNewRun(String conversationId, String userId, String request,
                                  PlanSpec.ShoppingConstraints constraints) {
        return planNewRun(UUID.randomUUID().toString(), UUID.randomUUID().toString(), conversationId,
                userId, request, constraints);
    }

    ShoppingAgentState planNewRun(String runId, String traceId, String conversationId, String userId,
                                  String request, PlanSpec.ShoppingConstraints constraints) {
        // 图节点可能在 checkpoint 写入前重放；已完成规划的 run 直接返回，避免重复写会话和重复调用 LLM。
        Optional<ShoppingAgentState> existing = store.find(runId);
        if (existing.isPresent()) {
            ShoppingAgentState state = existing.get();
            assertOwner(state, userId);
            if (!state.conversationId().equals(conversationId) || !state.originalRequest().equals(request)) {
                throw new RunStateConflictException("existing run does not match the original request");
            }
            // NEW 只是规划前占位。超时重试必须继续调模型，否则会拿空计划去搜商品。
            if (state.phase() != Phase.NEW) return state;
            return finishInitialPlan(state, constraints);
        }
        memory.appendUserMessage(conversationId, userId, request);
        // 先落 NEW，前端才能在 DeepSeek 返回前刷到进度，而不是一直停在“开始处理”。
        store.save(new ShoppingAgentState(runId, conversationId, userId, traceId, request,
                planningPlaceholder(constraints), Phase.NEW, List.of(), -1, null, null, null, 0, 0, 0,
                null, null, clock.instant()));
        return finishInitialPlan(store.find(runId).orElseThrow(), constraints);
    }

    private ShoppingAgentState finishInitialPlan(ShoppingAgentState pending,
                                                 PlanSpec.ShoppingConstraints constraints) {
        String contextualRequest = contextualRequest(memory.recentUserMessages(
                pending.conversationId(), pending.userId(), 8));
        PlanSpec plan = planValidator.validate(planningModel.createPlan(contextualRequest, constraints));
        Phase phase = plan.clarification().required() ? Phase.NEEDS_CLARIFICATION : Phase.SEARCHING;
        return store.save(new ShoppingAgentState(pending.runId(), pending.conversationId(), pending.userId(),
                pending.traceId(), pending.originalRequest(), plan, phase, List.of(), -1, null, null, null,
                0, 0, 1, null, null, clock.instant()));
    }

    private static PlanSpec planningPlaceholder(PlanSpec.ShoppingConstraints explicit) {
        PlanSpec.ShoppingConstraints constraints = explicit != null ? explicit
                : new PlanSpec.ShoppingConstraints("", null, null, null, List.of(), List.of(), Map.of(), 1, null, null, 1);
        return new PlanSpec(PlanSpec.IntentType.PRODUCT_DISCOVERY, constraints,
                new PlanSpec.Clarification(true, List.of(), "正在理解你的购物需求"),
                PlanSpec.SearchStrategy.HYBRID, List.of(), List.of(),
                PlanSpec.FallbackPolicy.safeDefault(), "planning");
    }

    public ShoppingAgentState get(String runId, String userId) {
        ShoppingAgentState state = store.find(runId)
                .orElseThrow(() -> new IllegalArgumentException("run not found: " + runId));
        assertOwner(state, userId);
        return state;
    }

    Optional<ShoppingAgentState> find(String runId) {
        return store.find(runId);
    }

    public List<ShoppingAgentState> recent(String userId, int limit) {
        return store.findRecentByUser(userId, limit);
    }

    // ===== 人工澄清与显式约束放宽 =============================================

    public ShoppingAgentState clarify(String runId, String userId, String message) {
        ShoppingAgentState clarified = applyClarification(runId, userId, message);
        return clarified.phase() == Phase.SEARCHING ? search(runId, userId) : clarified;
    }

    ShoppingAgentState applyClarification(String runId, String userId, String message) {
        ShoppingAgentState state = get(runId, userId);
        if (state.phase() != Phase.NEEDS_CLARIFICATION) {
            throw new RunStateConflictException("run is not waiting for clarification");
        }
        memory.appendUserMessage(state.conversationId(), userId, message);
        PlanSpec plan = planValidator.validate(planningModel.createPlan(
                contextualRequest(memory.recentUserMessages(state.conversationId(), userId, 8)),
                state.planSpec().normalizedConstraints()));
        Phase phase = plan.clarification().required() ? Phase.NEEDS_CLARIFICATION : Phase.SEARCHING;
        ShoppingAgentState clarified = new ShoppingAgentState(state.runId(), state.conversationId(),
                state.userId(), state.traceId(), state.originalRequest(), plan, phase,
                List.of(), -1, null, null, null, 0, 0, state.planVersion() + 1,
                null, null, clock.instant());
        store.save(clarified);
        return clarified;
    }

    ShoppingAgentState search(String runId, String userId) {
        return executeSearch(get(runId, userId));
    }

    ShoppingAgentState applyConstraintRelaxation(String runId, String userId, String instruction, String fieldsCsv) {
        ShoppingAgentState state = get(runId, userId);
        if (state.phase() != Phase.NEEDS_CONSTRAINT_RELAXATION) {
            throw new RunStateConflictException("run is not waiting for constraint relaxation");
        }
        memory.appendUserMessage(state.conversationId(), userId, instruction);
        PlanSpec relaxed = planValidator.validate(planningModel.relaxConstraints(state.originalRequest(),
                state.planSpec().normalizedConstraints(), instruction, parseFields(fieldsCsv)));
        if (relaxed.normalizedConstraints().version() != state.planSpec().normalizedConstraints().version() + 1) {
            throw new IllegalStateException("constraint relaxation must increment exactly one version");
        }
        if (!java.util.Objects.equals(relaxed.normalizedConstraints().addressId(),
                state.planSpec().normalizedConstraints().addressId())) {
            throw new IllegalStateException("constraint relaxation cannot change the authenticated address");
        }
        ShoppingAgentState updated = new ShoppingAgentState(state.runId(), state.conversationId(), state.userId(),
                state.traceId(), state.originalRequest(), relaxed, Phase.SEARCHING, List.of(), -1, null, null, null,
                state.candidateFallbackCount(), 0, state.planVersion() + 1, null, null, clock.instant());
        return store.save(updated);
    }

    public ShoppingAgentState selectCandidate(String runId, String userId, String skuId) {
        ShoppingAgentState selected = recordCandidateSelection(runId, userId, skuId);
        return prepareSnapshot(selected);
    }

    // ===== 候选选择、权威快照与库存预占 =======================================

    ShoppingAgentState recordCandidateSelection(String runId, String userId, String skuId) {
        ShoppingAgentState state = get(runId, userId);
        if ((state.phase() == Phase.WAITING_APPROVAL || state.phase() == Phase.CREATING_ORDER
                || state.phase() == Phase.COMPLETED) && state.selectedCandidateIndex() >= 0
                && state.candidateSet().get(state.selectedCandidateIndex()).skuId().equals(skuId)) {
            return state;
        }
        if (state.phase() != Phase.PRESENTING_CANDIDATES) {
            throw new RunStateConflictException("run is not waiting for candidate selection");
        }
        int index = indexOfCandidate(state.candidateSet(), skuId);
        if (index < 0) throw new IllegalArgumentException("sku is not part of the current candidate set");
        return store.save(copy(state, Phase.PREPARING_CONFIRMABLE_ORDER, index,
                null, null, null, state.candidateFallbackCount(), state.searchReplanCount(),
                state.planVersion(), null, null));
    }

    ShoppingAgentState prepareSelectedCandidate(String runId, String userId) {
        ShoppingAgentState state = get(runId, userId);
        // 业务状态可能已保存、图 checkpoint 尚未来得及保存。节点重放时直接返回已推进状态。
        if (state.phase() == Phase.WAITING_APPROVAL || state.phase() == Phase.PRESENTING_CANDIDATES
                || state.phase() == Phase.NEEDS_CONSTRAINT_RELAXATION) return state;
        if (state.phase() != Phase.PREPARING_CONFIRMABLE_ORDER || state.selectedCandidateIndex() < 0) {
            throw new RunStateConflictException("run has no selected candidate to prepare");
        }
        return prepareSnapshot(state);
    }

    public ShoppingAgentState approve(String runId, String userId, String snapshotId, String expectedSummaryHash) {
        ShoppingAgentState state = get(runId, userId);
        if (state.phase() == Phase.COMPLETED) {
            if (state.confirmableSnapshot() != null
                    && state.confirmableSnapshot().snapshotId().equals(snapshotId)
                    && state.confirmableSnapshot().summaryHash().equals(expectedSummaryHash)) return state;
            throw new RunStateConflictException("completed run belongs to a different approval snapshot");
        }
        if ((state.phase() != Phase.WAITING_APPROVAL && state.phase() != Phase.CREATING_ORDER)
                || state.confirmableSnapshot() == null || state.pendingApproval() == null) {
            throw new RunStateConflictException("run is not waiting for approval");
        }
        ConfirmableOrderSnapshot snapshot = state.confirmableSnapshot();
        if (!snapshot.snapshotId().equals(snapshotId) || !snapshot.summaryHash().equals(expectedSummaryHash)) {
            throw new IllegalArgumentException("approval does not match the current snapshot");
        }
        if (!clock.instant().isBefore(snapshot.expiresAt())) {
            ShoppingAgentState released = releaseCurrentReservation(state, "expired-before-approval");
            // 过期快照已经是一次完成的副作用。新报价必须推进 planVersion，从而生成新的 effectId；
            // 否则 Commerce effect ledger 会重放第一次已经过期的快照，形成无限循环。
            ShoppingAgentState requote = copy(released, Phase.PREPARING_CONFIRMABLE_ORDER,
                    released.selectedCandidateIndex(), null, null, released.activeEffect(),
                    released.candidateFallbackCount(), released.searchReplanCount(), released.planVersion() + 1,
                    "approval snapshot expired; preparing a fresh quote", null);
            store.save(requote);
            return prepareSnapshot(requote);
        }

        ApprovalProof approval = new ApprovalProof(state.pendingApproval().approvalRequestId(), snapshotId,
                expectedSummaryHash, userId, clock.instant(), snapshot.expiresAt());
        if (state.phase() == Phase.CREATING_ORDER) {
            ActiveEffect active = state.activeEffect();
            if (active == null || active.status() != EffectStatus.PENDING_EFFECT
                    || !active.requestHash().equals(hash(snapshotId, expectedSummaryHash))) {
                throw new IllegalStateException("order effect cannot be safely resumed");
            }
            return finishCreateOrder(state, approval, active.effectId());
        }

        String effectId = effectId(state, "create-order", 0);
        ActiveEffect pending = new ActiveEffect(effectId, "CREATE_ORDER",
                hash(snapshotId, expectedSummaryHash), EffectStatus.PENDING_EFFECT);
        state = store.save(copy(state, Phase.CREATING_ORDER, state.selectedCandidateIndex(), snapshot,
                state.pendingApproval(), pending, state.candidateFallbackCount(), state.searchReplanCount(),
                state.planVersion(), null, null));

        return finishCreateOrder(state, approval, effectId);
    }

    // ===== 最终审批、下单与取消 ===============================================

    private ShoppingAgentState finishCreateOrder(ShoppingAgentState state, ApprovalProof approval, String effectId) {
        ConfirmableOrderSnapshot snapshot = state.confirmableSnapshot();
        EffectContext effect = effectContext(state, effectId, "create-order", 0);
        Order order;
        try {
            order = commerce.createOrder(new CreateOrderCommand(state.userId(), snapshot.snapshotId(), approval), effect);
        } catch (CommerceOperationException failure) {
            if (!List.of("RESERVATION_NOT_ACTIVE", "APPROVAL_EXPIRED").contains(failure.code())) throw failure;
            ShoppingAgentState released = releaseCurrentReservation(state, "expired-during-order-create");
            ShoppingAgentState requote = copy(released, Phase.PREPARING_CONFIRMABLE_ORDER,
                    released.selectedCandidateIndex(), null, null, released.activeEffect(),
                    released.candidateFallbackCount(), released.searchReplanCount(), released.planVersion() + 1,
                    failure.getMessage(), null);
            store.save(requote);
            return prepareSnapshot(requote);
        }
        ActiveEffect applied = new ActiveEffect(effectId, "CREATE_ORDER", state.activeEffect().requestHash(),
                EffectStatus.EFFECT_APPLIED);
        return store.save(copy(state, Phase.COMPLETED, state.selectedCandidateIndex(), snapshot,
                null, applied, state.candidateFallbackCount(), state.searchReplanCount(),
                state.planVersion(), null, order));
    }

    public ShoppingAgentState reject(String runId, String userId) {
        ShoppingAgentState state = get(runId, userId);
        if (state.phase() != Phase.WAITING_APPROVAL) {
            throw new RunStateConflictException("run is not waiting for approval");
        }
        state = releaseCurrentReservation(state, "user-rejected");
        return store.save(copy(state, Phase.CANCELLED, state.selectedCandidateIndex(), null,
                null, state.activeEffect(), state.candidateFallbackCount(), state.searchReplanCount(),
                state.planVersion(), null, null));
    }

    ShoppingAgentState cancel(String runId, String userId) {
        ShoppingAgentState state = get(runId, userId);
        if (state.phase() == Phase.COMPLETED) {
            throw new RunStateConflictException("completed order cannot be cancelled by the shopping workflow");
        }
        if (state.phase() == Phase.CANCELLED) return state;
        if (state.phase() == Phase.CREATING_ORDER) {
            // 下单 HTTP 超时代表结果未知。必须先用同一 effectId 恢复订单结果，不能把它伪装成取消成功。
            throw new RunStateConflictException("order creation is being resolved and cannot be cancelled yet");
        }
        if (state.phase() == Phase.PREPARING_CONFIRMABLE_ORDER
                && state.activeEffect() != null
                && state.activeEffect().status() == EffectStatus.PENDING_EFFECT) {
            // 预占请求可能已经在 Commerce 成功。复用原 effectId 取回结果后再幂等释放库存。
            state = prepareSnapshot(state);
        }
        if (state.confirmableSnapshot() != null) {
            state = releaseCurrentReservation(state, "user-cancelled");
        }
        return store.save(copy(state, Phase.CANCELLED, state.selectedCandidateIndex(), null,
                null, state.activeEffect(), state.candidateFallbackCount(), state.searchReplanCount(),
                state.planVersion(), null, null));
    }

    private ShoppingAgentState executeSearch(ShoppingAgentState state) {
        // 搜索只传递用户硬约束；Commerce 会按当前库存数量、价格和履约能力重新筛选。
        PlanSpec.ShoppingConstraints c = state.planSpec().normalizedConstraints();
        SearchResult result = commerce.searchProducts(new SearchRequest(state.userId(), c.query(), c.category(),
                c.budgetMax(), c.budgetMin(),
                c.excludedBrands(), c.requiredAttributes(), c.addressId(), c.deliveryBy(), c.quantity(), 10));
        List<ProductCandidate> rankedCandidates = rankCandidates(result.candidates(), state.planSpec());
        if (rankedCandidates.isEmpty()) {
            ReplanController.ReplanDecision decision = replanController.decide(state);
            if (decision.level() == ReplanController.ReplanLevel.SEARCH_REPLAN) {
                return replanAndSearch(state, "no catalog candidate satisfied all hard constraints");
            }
            return store.save(copy(state, Phase.NEEDS_CONSTRAINT_RELAXATION, -1, null, null, null,
                    state.candidateFallbackCount(), state.searchReplanCount(), state.planVersion(),
                    "no product satisfies the hard constraints", null));
        }
        return store.save(new ShoppingAgentState(state.runId(), state.conversationId(), state.userId(),
                state.traceId(), state.originalRequest(), state.planSpec(), Phase.PRESENTING_CANDIDATES,
                rankedCandidates, -1, null, null, null, state.candidateFallbackCount(),
                state.searchReplanCount(), state.planVersion(), null, null, clock.instant()));
    }

    private static List<ProductCandidate> rankCandidates(List<ProductCandidate> candidates, PlanSpec plan) {
        // 排序偏好只改变展示顺序，绝不会改变预算、品牌排除等硬过滤结果。
        PlanSpec.ShoppingConstraints constraints = plan.normalizedConstraints();
        Comparator<ProductCandidate> comparator = Comparator.comparing(candidate -> 0);
        for (PlanSpec.RankingPreference preference : plan.rankingPreferences().reversed()) {
            Comparator<ProductCandidate> next = switch (preference) {
                case PRICE -> {
                    boolean floorOnly = constraints.budgetMin() != null && constraints.budgetMax() == null;
                    Comparator<ProductCandidate> byPrice = Comparator.comparing(
                            candidate -> candidate.displayPrice().amount());
                    yield floorOnly ? byPrice.reversed() : byPrice;
                }
                case DELIVERY -> Comparator.comparing(ProductCandidate::deliveryDate);
                case BRAND_PREFERENCE -> Comparator.comparingInt(candidate -> {
                    int index = indexIgnoreCase(constraints.preferredBrands(), candidate.brand());
                    return index < 0 ? Integer.MAX_VALUE : index;
                });
                case SPEC_MATCH -> Comparator.comparingInt(candidate ->
                        -matchingAttributes(candidate, constraints));
            };
            comparator = next.thenComparing(comparator);
        }
        return candidates.stream().sorted(comparator
                .thenComparing(ProductCandidate::productId)
                .thenComparing(ProductCandidate::skuId)).toList();
    }

    private static int matchingAttributes(ProductCandidate candidate, PlanSpec.ShoppingConstraints constraints) {
        int matches = 0;
        for (var entry : constraints.requiredAttributes().entrySet()) {
            String actual = candidate.attributes().get(entry.getKey());
            if (actual != null && actual.equalsIgnoreCase(entry.getValue())) matches++;
        }
        return matches;
    }

    private static int indexIgnoreCase(List<String> values, String target) {
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).equalsIgnoreCase(target)) return index;
        }
        return -1;
    }

    private ShoppingAgentState prepareSnapshot(ShoppingAgentState state) {
        ProductCandidate candidate = state.candidateSet().get(state.selectedCandidateIndex());
        PlanSpec.ShoppingConstraints c = state.planSpec().normalizedConstraints();
        int logicalAttempt = state.candidateFallbackCount();
        String effectId = effectId(state, "prepare-snapshot", logicalAttempt);
        String requestHash = hash(state.userId(), candidate.skuId(), String.valueOf(c.quantity()), c.addressId(),
                moneyKey(c.budgetMax()), moneyKey(c.budgetMin()));
        // 先把 PENDING_EFFECT 保存，再调用外部 Commerce。崩溃后使用相同 effectId 可重放成功结果。
        ActiveEffect pending = new ActiveEffect(effectId, "PREPARE_CONFIRMABLE_ORDER", requestHash,
                EffectStatus.PENDING_EFFECT);
        state = store.save(copy(state, Phase.PREPARING_CONFIRMABLE_ORDER, state.selectedCandidateIndex(),
                null, null, pending, state.candidateFallbackCount(), state.searchReplanCount(),
                state.planVersion(), null, null));
        try {
            EffectContext effect = effectContext(state, effectId, "prepare-snapshot", logicalAttempt);
            ConfirmableOrderSnapshot snapshot = commerce.prepareConfirmableOrder(
                    new PrepareOrderRequest(state.userId(), candidate.skuId(), c.quantity(), c.addressId(),
                            c.budgetMax(), c.budgetMin()), effect);
            PendingApproval approval = new PendingApproval(UUID.randomUUID().toString(), snapshot.snapshotId(),
                    snapshot.summaryHash(), snapshot.expiresAt());
            ActiveEffect applied = new ActiveEffect(effectId, "PREPARE_CONFIRMABLE_ORDER", requestHash,
                    EffectStatus.EFFECT_APPLIED);
            return store.save(copy(state, Phase.WAITING_APPROVAL, state.selectedCandidateIndex(), snapshot,
                    approval, applied, state.candidateFallbackCount(), state.searchReplanCount(),
                    state.planVersion(), null, null));
        } catch (CommerceOperationException failure) {
            boolean stockGone = List.of("OUT_OF_STOCK", "SKU_NOT_FOUND").contains(failure.code());
            boolean overBudget = List.of("BUDGET_EXCEEDED", "BUDGET_BELOW_MINIMUM").contains(failure.code());
            if (!stockGone && !overBudget) throw failure;
            // 超预算不能靠“换搜索词”偷偷抬预算，只换下一个候选或请用户明确放宽。
            return fallbackAfterPrepareFailure(state, failure.getMessage(), !overBudget);
        }
    }

    private ShoppingAgentState fallbackAfterPrepareFailure(ShoppingAgentState state, String reason,
                                                          boolean allowSearchReplan) {
        // 只有缺货/下架才进入三级回退；权限、地址或协议错误必须直接暴露，不能被“换商品”掩盖。
        ReplanController.ReplanDecision decision = replanController.decide(state);
        return switch (decision.level()) {
            case CANDIDATE_FALLBACK -> prepareSnapshot(copy(state, Phase.PREPARING_CONFIRMABLE_ORDER,
                    decision.candidateIndex(), null, null, null, state.candidateFallbackCount() + 1,
                    state.searchReplanCount(), state.planVersion(), reason, null));
            case SEARCH_REPLAN -> allowSearchReplan
                    ? replanAndSearch(state,
                    "catalog candidates became unavailable while reserving inventory: " + reason)
                    : store.save(copy(state, Phase.NEEDS_CONSTRAINT_RELAXATION, -1,
                    null, null, null, state.candidateFallbackCount(), state.searchReplanCount(),
                    state.planVersion(), reason, null));
            case CONSTRAINT_RELAXATION -> store.save(copy(state, Phase.NEEDS_CONSTRAINT_RELAXATION, -1,
                    null, null, null, state.candidateFallbackCount(), state.searchReplanCount(),
                    state.planVersion(), reason, null));
        };
    }

    private ShoppingAgentState replanAndSearch(ShoppingAgentState state, String reason) {
        // Search Replan 允许修改搜索表达和策略，但 PlanningModel 必须保留全部硬约束。
        int nextAttempt = state.searchReplanCount() + 1;
        PlanSpec replanned = planValidator.validate(planningModel.replan(state.originalRequest(),
                state.planSpec().normalizedConstraints(), reason, nextAttempt));
        ShoppingAgentState replanning = new ShoppingAgentState(state.runId(), state.conversationId(),
                state.userId(), state.traceId(), state.originalRequest(), replanned, Phase.SEARCHING,
                List.of(), -1, null, null, null, state.candidateFallbackCount(), nextAttempt,
                state.planVersion() + 1, reason, null, clock.instant());
        store.save(replanning);
        return executeSearch(replanning);
    }

    private ShoppingAgentState releaseCurrentReservation(ShoppingAgentState state, String reason) {
        ConfirmableOrderSnapshot snapshot = state.confirmableSnapshot();
        if (snapshot == null) return state;
        String effectId = effectId(state, "release-reservation-" + reason, 0);
        commerce.releaseReservation(snapshot.reservation().reservationId(),
                effectContext(state, effectId, "release-reservation", 0));
        return copy(state, state.phase(), state.selectedCandidateIndex(), snapshot,
                state.pendingApproval(), new ActiveEffect(effectId, "RELEASE_RESERVATION",
                        hash(snapshot.reservation().reservationId()), EffectStatus.EFFECT_APPLIED),
                state.candidateFallbackCount(), state.searchReplanCount(), state.planVersion(), reason, null);
    }

    private EffectContext effectContext(ShoppingAgentState state, String effectId, String nodeId, int attempt) {
        return new EffectContext(effectId, effectId, state.runId(), nodeId, attempt,
                state.userId(), state.traceId());
    }

    private String effectId(ShoppingAgentState state, String nodeId, int logicalAttempt) {
        // run、节点、计划版本和逻辑尝试共同确定副作用身份；进程重启后计算结果仍相同。
        return hash(state.runId(), nodeId, String.valueOf(state.planVersion()), String.valueOf(logicalAttempt));
    }

    private static String moneyKey(com.buyforu.commerce.port.model.CommerceModels.Money money) {
        return money == null ? "" : money.amount().toPlainString() + "\u001f" + money.currency();
    }

    private static int indexOfCandidate(List<ProductCandidate> candidates, String skuId) {
        for (int index = 0; index < candidates.size(); index++) {
            if (candidates.get(index).skuId().equals(skuId)) return index;
        }
        return -1;
    }

    private static String contextualRequest(List<String> messages) {
        StringBuilder result = new StringBuilder("User conversation in chronological order:\n");
        for (String message : messages) result.append("- ").append(message).append('\n');
        return result.toString();
    }

    private static java.util.List<String> parseFields(String fieldsCsv) {
        if (fieldsCsv == null || fieldsCsv.isBlank()) return java.util.List.of();
        return java.util.Arrays.stream(fieldsCsv.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    private static void assertOwner(ShoppingAgentState state, String userId) {
        if (!state.userId().equals(userId)) throw new SecurityException("run belongs to another user");
    }

    private ShoppingAgentState copy(ShoppingAgentState s, Phase phase, int selectedIndex,
                                    ConfirmableOrderSnapshot snapshot, PendingApproval approval,
                                    ActiveEffect effect, int fallbackCount, int replanCount,
                                    long planVersion, String error, Order order) {
        return new ShoppingAgentState(s.runId(), s.conversationId(), s.userId(), s.traceId(),
                s.originalRequest(), s.planSpec(), phase, s.candidateSet(), selectedIndex, snapshot,
                approval, effect, fallbackCount, replanCount, planVersion, error, order, clock.instant());
    }

    private static String hash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(String.join("\u001f", values)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
