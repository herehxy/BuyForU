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
}
