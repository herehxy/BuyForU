package com.buyforu.agent.application;

import com.buyforu.agent.domain.PlanSpec;
import com.buyforu.agent.domain.ShoppingAgentState;
import com.buyforu.agent.infrastructure.memory.InMemoryAgentRunStore;
import com.buyforu.commerce.application.InMemoryCommerceEngine;
import com.buyforu.commerce.port.model.CommerceModels.Money;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ShoppingWorkflowService 的用例测试：覆盖主交易链、用户隔离、人工澄清和副作用崩溃恢复。
 */
class ShoppingWorkflowServiceTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-12T08:00:00Z"), ZoneOffset.UTC);

    @Test
    void completesSelectionReservationApprovalAndOrderFlow() {
        InMemoryCommerceEngine commerce = InMemoryCommerceEngine.seeded(clock);
        ShoppingWorkflowService workflow = new ShoppingWorkflowService(commerce,
                new DeterministicPlanningModel(), new InMemoryAgentRunStore(), new InMemoryConversationMemory(), clock);

        ShoppingAgentState searched = workflow.start("conversation-1", "user-1", "5000 元以内的轻薄本",
                constraints());
        assertEquals(ShoppingAgentState.Phase.PRESENTING_CANDIDATES, searched.phase());
        assertFalse(searched.candidateSet().isEmpty());

        ShoppingAgentState waiting = workflow.selectCandidate(searched.runId(), "user-1",
                searched.candidateSet().getFirst().skuId());
        assertEquals(ShoppingAgentState.Phase.WAITING_APPROVAL, waiting.phase());
        assertNotNull(waiting.confirmableSnapshot().reservation());

        ShoppingAgentState completed = workflow.approve(waiting.runId(), "user-1",
                waiting.confirmableSnapshot().snapshotId(), waiting.confirmableSnapshot().summaryHash());
        assertEquals(ShoppingAgentState.Phase.COMPLETED, completed.phase());
        assertNotNull(completed.finalOrder());
        assertEquals(1, commerce.orderCount());

        ShoppingAgentState repeated = workflow.approve(waiting.runId(), "user-1",
                waiting.confirmableSnapshot().snapshotId(), waiting.confirmableSnapshot().summaryHash());
        assertEquals(completed.finalOrder().orderId(), repeated.finalOrder().orderId());
        assertEquals(1, commerce.orderCount());
    }

    @Test
    void enforcesRunOwnership() {
        ShoppingWorkflowService workflow = new ShoppingWorkflowService(InMemoryCommerceEngine.seeded(clock),
                new DeterministicPlanningModel(), new InMemoryAgentRunStore(), new InMemoryConversationMemory(), clock);
        ShoppingAgentState state = workflow.start("conversation-1", "owner", "laptop", constraints());
        assertThrows(SecurityException.class, () -> workflow.get(state.runId(), "attacker"));
    }

    @Test
    void resumesSameOrderEffectAfterCheckpointFailure() {
        InMemoryCommerceEngine commerce = InMemoryCommerceEngine.seeded(clock);
        InMemoryAgentRunStore delegate = new InMemoryAgentRunStore();
        AtomicBoolean failCompletedCheckpointOnce = new AtomicBoolean(true);
        // 故障注入：Commerce 已创建订单，但保存 COMPLETED 状态第一次失败。
        // 第二次批准必须使用相同 effectId 取回原订单，而不是创建第二单。
        AgentRunStore flakyStore = new AgentRunStore() {
            @Override
            public ShoppingAgentState save(ShoppingAgentState state) {
                if (state.phase() == ShoppingAgentState.Phase.COMPLETED
                        && failCompletedCheckpointOnce.compareAndSet(true, false)) {
                    throw new IllegalStateException("simulated checkpoint failure");
                }
                return delegate.save(state);
            }

            @Override
            public Optional<ShoppingAgentState> find(String runId) {
                return delegate.find(runId);
            }

            @Override
            public List<ShoppingAgentState> findRecentByUser(String userId, int limit) {
                return delegate.findRecentByUser(userId, limit);
            }

            @Override
            public ShoppingAgentState update(String runId, UnaryOperator<ShoppingAgentState> mutation) {
                return delegate.update(runId, mutation);
            }
        };
        ShoppingWorkflowService workflow = new ShoppingWorkflowService(commerce,
                new DeterministicPlanningModel(), flakyStore, new InMemoryConversationMemory(), clock);

        ShoppingAgentState searched = workflow.start("conversation-crash", "user-1", "laptop", constraints());
        ShoppingAgentState waiting = workflow.selectCandidate(searched.runId(), "user-1",
                searched.candidateSet().getFirst().skuId());

        assertThrows(IllegalStateException.class, () -> workflow.approve(waiting.runId(), "user-1",
                waiting.confirmableSnapshot().snapshotId(), waiting.confirmableSnapshot().summaryHash()));
        assertEquals(ShoppingAgentState.Phase.CREATING_ORDER,
                workflow.get(waiting.runId(), "user-1").phase());

        ShoppingAgentState recovered = workflow.approve(waiting.runId(), "user-1",
                waiting.confirmableSnapshot().snapshotId(), waiting.confirmableSnapshot().summaryHash());
        assertEquals(ShoppingAgentState.Phase.COMPLETED, recovered.phase());
        assertEquals(1, commerce.orderCount());
    }

    @Test
    void expiredApprovalCreatesFreshSnapshotInsteadOfReplayingExpiredEffect() {
        MutableClock mutableClock = new MutableClock(Instant.parse("2026-08-12T08:00:00Z"));
        InMemoryCommerceEngine commerce = InMemoryCommerceEngine.seeded(mutableClock);
        ShoppingWorkflowService workflow = new ShoppingWorkflowService(commerce,
                new DeterministicPlanningModel(), new InMemoryAgentRunStore(), new InMemoryConversationMemory(),
                mutableClock);

        ShoppingAgentState searched = workflow.start("conversation-expired", "user-1", "laptop", constraints());
        ShoppingAgentState first = workflow.selectCandidate(searched.runId(), "user-1",
                searched.candidateSet().getFirst().skuId());
        mutableClock.advance(Duration.ofMinutes(6));

        ShoppingAgentState refreshed = workflow.approve(first.runId(), "user-1",
                first.confirmableSnapshot().snapshotId(), first.confirmableSnapshot().summaryHash());
        assertEquals(ShoppingAgentState.Phase.WAITING_APPROVAL, refreshed.phase());
        assertNotEquals(first.confirmableSnapshot().snapshotId(), refreshed.confirmableSnapshot().snapshotId());
        assertEquals(first.planVersion() + 1, refreshed.planVersion());

        ShoppingAgentState completed = workflow.approve(refreshed.runId(), "user-1",
                refreshed.confirmableSnapshot().snapshotId(), refreshed.confirmableSnapshot().summaryHash());
        assertEquals(ShoppingAgentState.Phase.COMPLETED, completed.phase());
        assertEquals(1, commerce.orderCount());
    }

    @Test
    void acceptsClarificationAndContinuesSearch() {
        PlanningModel model = new PlanningModel() {
            private final DeterministicPlanningModel delegate = new DeterministicPlanningModel();

            @Override
            public PlanSpec createPlan(String request, PlanSpec.ShoppingConstraints constraints) {
                PlanSpec base = delegate.createPlan(request, constraints);
                if (!request.contains("使用 address-1")) {
                    return new PlanSpec(base.intentType(), base.normalizedConstraints(),
                            new PlanSpec.Clarification(true, List.of("addressId"), "收货地址是什么？"),
                            base.searchStrategy(), List.of(), base.rankingPreferences(), base.fallbackPolicy(), "ask");
                }
                return base;
            }

            @Override
            public PlanSpec replan(String request, PlanSpec.ShoppingConstraints constraints,
                                   String reason, int attempt) {
                return delegate.replan(request, constraints, reason, attempt);
            }

            @Override
            public PlanSpec relaxConstraints(String request, PlanSpec.ShoppingConstraints constraints,
                                              String instruction, List<String> fields) {
                return delegate.relaxConstraints(request, constraints, instruction, fields);
            }
        };
        ShoppingWorkflowService workflow = new ShoppingWorkflowService(InMemoryCommerceEngine.seeded(clock),
                model, new InMemoryAgentRunStore(), new InMemoryConversationMemory(), clock);

        ShoppingAgentState waiting = workflow.start("conversation", "user", "laptop", constraints());
        assertEquals(ShoppingAgentState.Phase.NEEDS_CLARIFICATION, waiting.phase());
        ShoppingAgentState searched = workflow.clarify(waiting.runId(), "user", "使用 address-1");
        assertEquals(ShoppingAgentState.Phase.PRESENTING_CANDIDATES, searched.phase());
        assertEquals("laptop", searched.originalRequest());
    }

    @Test
    void continuesPlanningAfterPersistedNewPlaceholder() {
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        PlanningModel model = new PlanningModel() {
            private final DeterministicPlanningModel delegate = new DeterministicPlanningModel();

            @Override
            public PlanSpec createPlan(String request, PlanSpec.ShoppingConstraints constraints) {
                if (failOnce.compareAndSet(true, false)) {
                    throw new IllegalStateException("simulated planning timeout");
                }
                return delegate.createPlan(request, constraints);
            }

            @Override
            public PlanSpec replan(String request, PlanSpec.ShoppingConstraints constraints,
                                   String reason, int attempt) {
                return delegate.replan(request, constraints, reason, attempt);
            }

            @Override
            public PlanSpec relaxConstraints(String request, PlanSpec.ShoppingConstraints constraints,
                                              String instruction, List<String> fields) {
                return delegate.relaxConstraints(request, constraints, instruction, fields);
            }
        };
        ShoppingWorkflowService workflow = new ShoppingWorkflowService(InMemoryCommerceEngine.seeded(clock),
                model, store, new InMemoryConversationMemory(), clock);

        assertThrows(IllegalStateException.class,
                () -> workflow.planNewRun("run-new", "trace", "conversation", "user", "laptop", constraints()));
        assertEquals(ShoppingAgentState.Phase.NEW, store.find("run-new").orElseThrow().phase());

        ShoppingAgentState planned = workflow.planNewRun("run-new", "trace", "conversation", "user",
                "laptop", constraints());
        assertEquals(ShoppingAgentState.Phase.SEARCHING, planned.phase());
        assertTrue(planned.planSpec().readTasks().contains(PlanSpec.ReadTask.SEARCH_PRODUCTS));
    }

    private PlanSpec.ShoppingConstraints constraints() {
        return new PlanSpec.ShoppingConstraints("", "laptop", Money.cny("5000"), null, List.of(), List.of(),
                Map.of("memory", "16GB"), 1, "address-1", LocalDate.of(2026, 8, 13), 1);
    }

    /** 可控时钟让快照过期测试不依赖 sleep，测试速度和结果都保持稳定。 */
    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
