package com.buyforu.agent.application;

import com.buyforu.agent.domain.PlanSpec;
import com.buyforu.agent.domain.ShoppingAgentState;
import com.buyforu.agent.infrastructure.memory.InMemoryAgentRunStore;
import com.buyforu.commerce.application.InMemoryCommerceEngine;
import com.buyforu.commerce.port.model.CommerceModels.Money;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证图门面真实调用节点动作、人工恢复和启动/选择命令幂等。 */
class GraphShoppingWorkflowTest {
    @Test
    void executesRealNodesAndRequiresSelectionAndApprovalResumes() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-12T08:00:00Z"), ZoneOffset.UTC);
        ShoppingWorkflowService actions = new ShoppingWorkflowService(InMemoryCommerceEngine.seeded(clock),
                new DeterministicPlanningModel(), new InMemoryAgentRunStore(), new InMemoryConversationMemory(), clock);
        var json = JsonMapper.builder().findAndAddModules().build();
        GraphShoppingWorkflow workflow = new GraphShoppingWorkflow(
                new FixedShoppingGraph(new MemorySaver(), actions, json), actions, json,
                (runId, action) -> action.run());
        var constraints = new PlanSpec.ShoppingConstraints("", "laptop", Money.cny("5000"), null, List.of(),
                List.of(), Map.of("memory", "16GB"), 1, "address-1", LocalDate.of(2026, 8, 13), 1);

        ShoppingAgentState candidates = workflow.start("conversation", "user", "5000元以内的笔记本", constraints,
                "request-1");
        assertEquals(ShoppingAgentState.Phase.PRESENTING_CANDIDATES, candidates.phase());

        ShoppingAgentState approval = workflow.selectCandidate(candidates.runId(), "user",
                candidates.candidateSet().getFirst().skuId());
        assertEquals(ShoppingAgentState.Phase.WAITING_APPROVAL, approval.phase());
        // 模拟选择请求已成功但响应丢失：重复相同 SKU 必须返回原快照，不能再次预占。
        ShoppingAgentState replayedSelection = workflow.selectCandidate(candidates.runId(), "user",
                candidates.candidateSet().getFirst().skuId());
        assertEquals(approval.confirmableSnapshot().snapshotId(), replayedSelection.confirmableSnapshot().snapshotId());
        assertThrows(RunStateConflictException.class, () -> workflow.selectCandidate(candidates.runId(), "user",
                candidates.candidateSet().getLast().skuId()));
        assertEquals(ShoppingAgentState.Phase.WAITING_APPROVAL, workflow.get(candidates.runId(), "user").phase());

        ShoppingAgentState completed = workflow.approve(approval.runId(), "user",
                approval.confirmableSnapshot().snapshotId(), approval.confirmableSnapshot().summaryHash());
        assertEquals(ShoppingAgentState.Phase.COMPLETED, completed.phase());
        assertNotNull(completed.finalOrder());
        ShoppingAgentState replayedStart = workflow.start("conversation", "user", "5000元以内的笔记本",
                constraints, "request-1");
        assertEquals(completed.runId(), replayedStart.runId());
        assertEquals(completed.finalOrder().orderId(), replayedStart.finalOrder().orderId());
    }

    @Test
    void repeatingSelectionDuringCreateDoesNotFinishTheOrder() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-12T08:00:00Z"), ZoneOffset.UTC);
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        InMemoryCommerceEngine commerce = InMemoryCommerceEngine.seeded(clock);
        ShoppingWorkflowService actions = new ShoppingWorkflowService(commerce,
                new DeterministicPlanningModel(), store, new InMemoryConversationMemory(), clock);
        var json = JsonMapper.builder().findAndAddModules().build();
        GraphShoppingWorkflow workflow = new GraphShoppingWorkflow(
                new FixedShoppingGraph(new MemorySaver(), actions, json), actions, json,
                (runId, action) -> action.run());
        var constraints = new PlanSpec.ShoppingConstraints("", "laptop", Money.cny("5000"), null, List.of(),
                List.of(), Map.of("memory", "16GB"), 1, "address-1", LocalDate.of(2026, 8, 13), 1);

        ShoppingAgentState waiting = workflow.selectCandidate(
                workflow.start("conversation-select-create", "user", "5000元以内的笔记本", constraints,
                        "request-select-create").runId(),
                "user",
                workflow.start("conversation-select-create", "user", "5000元以内的笔记本", constraints,
                        "request-select-create").candidateSet().getFirst().skuId());
        store.save(new ShoppingAgentState(waiting.runId(), waiting.conversationId(), waiting.userId(),
                waiting.traceId(), waiting.originalRequest(), waiting.planSpec(),
                ShoppingAgentState.Phase.CREATING_ORDER, waiting.candidateSet(), waiting.selectedCandidateIndex(),
                waiting.confirmableSnapshot(), waiting.pendingApproval(),
                new ShoppingAgentState.ActiveEffect("effect-create", "CREATE_ORDER", "hash",
                        ShoppingAgentState.EffectStatus.PENDING_EFFECT),
                0, 0, waiting.planVersion(), null, null, clock.instant()));

        assertThrows(RunStateConflictException.class, () -> workflow.selectCandidate(waiting.runId(), "user",
                waiting.candidateSet().get(waiting.selectedCandidateIndex()).skuId()));
        assertEquals(ShoppingAgentState.Phase.CREATING_ORDER, workflow.get(waiting.runId(), "user").phase());
        assertEquals(0, commerce.orderCount());
    }

    @Test
    void recoverAfterExpiredRequoteCrashDoesNotCancel() {
        var clock = new MutableClock(Instant.parse("2026-08-12T08:00:00Z"));
        InMemoryAgentRunStore delegate = new InMemoryAgentRunStore();
        java.util.concurrent.atomic.AtomicBoolean crashOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        AgentRunStore store = new AgentRunStore() {
            @Override
            public ShoppingAgentState save(ShoppingAgentState state) {
                ShoppingAgentState saved = delegate.save(state);
                if (state.phase() == ShoppingAgentState.Phase.PREPARING_CONFIRMABLE_ORDER
                        && state.lastError() != null && state.lastError().contains("expired")
                        && crashOnce.compareAndSet(true, false)) {
                    throw new IllegalStateException("crash after requote flip");
                }
                return saved;
            }

            @Override
            public java.util.Optional<ShoppingAgentState> find(String runId) {
                return delegate.find(runId);
            }

            @Override
            public List<ShoppingAgentState> findRecentByUser(String userId, int limit) {
                return delegate.findRecentByUser(userId, limit);
            }

            @Override
            public ShoppingAgentState update(String runId, java.util.function.UnaryOperator<ShoppingAgentState> mutation) {
                return delegate.update(runId, mutation);
            }
        };
        ShoppingWorkflowService actions = new ShoppingWorkflowService(InMemoryCommerceEngine.seeded(clock),
                new DeterministicPlanningModel(), store, new InMemoryConversationMemory(), clock);
        var json = JsonMapper.builder().findAndAddModules().build();
        GraphShoppingWorkflow workflow = new GraphShoppingWorkflow(
                new FixedShoppingGraph(new MemorySaver(), actions, json), actions, json,
                (runId, action) -> action.run());
        var constraints = new PlanSpec.ShoppingConstraints("", "laptop", Money.cny("5000"), null, List.of(),
                List.of(), Map.of("memory", "16GB"), 1, "address-1", LocalDate.of(2026, 8, 13), 1);

        ShoppingAgentState approval = workflow.selectCandidate(
                workflow.start("conversation-requote", "user", "5000元以内的笔记本", constraints, "request-requote").runId(),
                "user",
                workflow.start("conversation-requote", "user", "5000元以内的笔记本", constraints, "request-requote")
                        .candidateSet().getFirst().skuId());
        clock.advance(Duration.ofMinutes(6));
        String expiredSnapshot = approval.confirmableSnapshot().snapshotId();
        String expiredHash = approval.confirmableSnapshot().summaryHash();
        assertThrows(RuntimeException.class,
                () -> workflow.approve(approval.runId(), "user", expiredSnapshot, expiredHash));
        assertEquals(ShoppingAgentState.Phase.PREPARING_CONFIRMABLE_ORDER,
                workflow.get(approval.runId(), "user").phase());

        ShoppingAgentState recovered = workflow.approve(approval.runId(), "user", expiredSnapshot, expiredHash);
        assertEquals(ShoppingAgentState.Phase.WAITING_APPROVAL, recovered.phase());
        assertNotNull(recovered.confirmableSnapshot());
        org.junit.jupiter.api.Assertions.assertNotEquals(expiredSnapshot, recovered.confirmableSnapshot().snapshotId());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
