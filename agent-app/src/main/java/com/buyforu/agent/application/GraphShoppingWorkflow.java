package com.buyforu.agent.application;

import com.buyforu.agent.domain.PlanSpec.ShoppingConstraints;
import com.buyforu.agent.domain.ShoppingAgentState;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 固定图的用例门面，Controller 只通过此类启动或恢复任务。
 * 这里负责命令幂等、用户所有权和每个 run 的串行执行；节点内业务由 ShoppingWorkflowService 完成。
 */
@Service
public class GraphShoppingWorkflow {
    private final FixedShoppingGraph graph;
    private final ShoppingWorkflowService actions;
    private final ObjectMapper json;
    private final RunExecutionCoordinator executionLock;

    public GraphShoppingWorkflow(FixedShoppingGraph graph, ShoppingWorkflowService actions, ObjectMapper json,
                                 RunExecutionCoordinator executionLock) {
        this.graph = graph;
        this.actions = actions;
        this.json = json;
        this.executionLock = executionLock;
    }

    public ShoppingAgentState start(String conversationId, String userId, String request,
                                    ShoppingConstraints constraints, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        // 相同用户和幂等键始终映射到相同 runId，因此“响应丢失后重试”不会创建第二个任务。
        String runId = UUID.nameUUIDFromBytes(("buyforu-run\u001f" + userId + "\u001f" + idempotencyKey)
                .getBytes(StandardCharsets.UTF_8)).toString();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("runId", runId);
        input.put("traceId", UUID.randomUUID().toString());
        input.put("conversationId", conversationId);
        input.put("userId", userId);
        input.put("request", request);
        if (constraints != null) input.put("constraintsJson", json.writeValueAsString(constraints));
        executionLock.execute(runId, () -> {
            java.util.Optional<ShoppingAgentState> existing = actions.find(runId);
            if (existing.isPresent()) {
                ShoppingAgentState state = existing.get();
                if (!state.userId().equals(userId)) throw new SecurityException("run belongs to another user");
                if (!state.conversationId().equals(conversationId) || !state.originalRequest().equals(request)) {
                    throw new RunStateConflictException("idempotencyKey was reused for a different run request");
                }
                recoverIfNecessary(state, input);
                return;
            }
            invoke(runId, GraphInput.args(input));
        });
        return actions.get(runId, userId);
    }

    public ShoppingAgentState clarify(String runId, String userId, String message) {
        assertOwner(runId, userId);
        resume(runId, "needClarification",
                Map.of("clarificationRoute", "provided", "clarificationMessage", message));
        return actions.get(runId, userId);
    }

    public ShoppingAgentState selectCandidate(String runId, String userId, String skuId) {
        ShoppingAgentState current = actions.get(runId, userId);
        if (current.phase() == ShoppingAgentState.Phase.CREATING_ORDER) {
            // 下单只能从 approve 恢复。重复选择同一 SKU 不能把 createOrder 跑完。
            throw new RunStateConflictException("order creation is being resolved and cannot be selected again");
        }
        if ((current.phase() == ShoppingAgentState.Phase.WAITING_APPROVAL
                || current.phase() == ShoppingAgentState.Phase.COMPLETED)
                && current.selectedCandidateIndex() >= 0
                && current.candidateSet().get(current.selectedCandidateIndex()).skuId().equals(skuId)) {
            executionLock.execute(runId, () -> recoverIfNecessary(actions.get(runId, userId), null));
            return actions.get(runId, userId);
        }
        if (current.phase() == ShoppingAgentState.Phase.PREPARING_CONFIRMABLE_ORDER
                && current.selectedCandidateIndex() >= 0
                && current.candidateSet().get(current.selectedCandidateIndex()).skuId().equals(skuId)) {
            executionLock.execute(runId, () -> recoverIfNecessary(actions.get(runId, userId), null));
            return actions.get(runId, userId);
        }
        resume(runId, "presentCandidates", Map.of("selectionRoute", "selected", "selectedSkuId", skuId));
        return actions.get(runId, userId);
    }

    public ShoppingAgentState approve(String runId, String userId, String snapshotId, String summaryHash) {
        ShoppingAgentState current = actions.get(runId, userId);
        if (current.phase() == ShoppingAgentState.Phase.COMPLETED) {
            if (current.confirmableSnapshot() != null
                    && current.confirmableSnapshot().snapshotId().equals(snapshotId)
                    && current.confirmableSnapshot().summaryHash().equals(summaryHash)) return current;
            throw new RunStateConflictException("completed run belongs to a different approval snapshot");
        }
        executionLock.execute(runId, () -> recoverIfNecessary(actions.get(runId, userId), null));
        ShoppingAgentState recovered = actions.get(runId, userId);
        if (recovered.phase() == ShoppingAgentState.Phase.COMPLETED) {
            if (recovered.confirmableSnapshot() != null
                    && recovered.confirmableSnapshot().snapshotId().equals(snapshotId)
                    && recovered.confirmableSnapshot().summaryHash().equals(summaryHash)) return recovered;
            throw new RunStateConflictException("completed run belongs to a different approval snapshot");
        }
        if (recovered.phase() != ShoppingAgentState.Phase.WAITING_APPROVAL
                && recovered.phase() != ShoppingAgentState.Phase.CREATING_ORDER) {
            // 过期重报价恢复后可能停在新的 WAITING 之外的阶段；不能再用旧 snapshot 去批准。
            return recovered;
        }
        if (recovered.phase() == ShoppingAgentState.Phase.WAITING_APPROVAL
                && recovered.confirmableSnapshot() != null
                && !recovered.confirmableSnapshot().snapshotId().equals(snapshotId)) {
            return recovered;
        }
        resume(runId, "awaitApproval", Map.of("approvalRoute", "approved", "snapshotId", snapshotId,
                "summaryHash", summaryHash));
        return actions.get(runId, userId);
    }

    public ShoppingAgentState reject(String runId, String userId) {
        return cancel(runId, userId);
    }

    public ShoppingAgentState cancel(String runId, String userId) {
        ShoppingAgentState current = actions.get(runId, userId);
        if (current.phase() == ShoppingAgentState.Phase.CANCELLED) return current;
        switch (current.phase()) {
            case NEEDS_CLARIFICATION -> resume(runId, "needClarification", Map.of("clarificationRoute", "cancel"));
            case PRESENTING_CANDIDATES -> resume(runId, "presentCandidates", Map.of("selectionRoute", "cancel"));
            case WAITING_APPROVAL -> resume(runId, "awaitApproval", Map.of("approvalRoute", "rejected"));
            case NEEDS_CONSTRAINT_RELAXATION -> resume(runId, "constraintRelaxation",
                    Map.of("relaxationRoute", "cancel"));
            case COMPLETED -> throw new RunStateConflictException("completed order cannot be cancelled by the agent");
            default -> executionLock.execute(runId, () -> actions.cancel(runId, userId));
        }
        return actions.get(runId, userId);
    }

    public ShoppingAgentState relax(String runId, String userId, String explicitInstruction,
                                    java.util.List<String> fields) {
        assertOwner(runId, userId);
        resume(runId, "constraintRelaxation",
                Map.of("relaxationRoute", "approved", "relaxationMessage", explicitInstruction,
                        "relaxationFields", fields == null ? "" : String.join(",", fields)));
        return actions.get(runId, userId);
    }

    public ShoppingAgentState get(String runId, String userId) {
        return actions.get(runId, userId);
    }

    public java.util.List<ShoppingAgentState> recent(String userId, int limit) {
        return actions.recent(userId, limit);
    }

    private void assertOwner(String runId, String userId) {
        actions.get(runId, userId);
    }

    private void invoke(String runId, GraphInput input) {
        graph.compiledGraph().invoke(input, RunnableConfig.builder().threadId(runId).build())
                .orElseThrow(() -> new IllegalStateException("shopping graph returned no state"));
    }

    private void recoverIfNecessary(ShoppingAgentState state, Map<String, Object> originalInput) {
        // 业务状态和图 checkpoint 分开持久化。若进程恰好在二者之间崩溃，这里根据业务阶段推进或重建图。
        RunnableConfig config = RunnableConfig.builder().threadId(state.runId()).build();
        try {
            String lastNode = graph.compiledGraph().getState(config).state().<String>value("lastNode").orElse("");
            if (lastNode.isBlank()) {
                if (originalInput == null) {
                    throw new RunStateConflictException("run has no recoverable graph checkpoint");
                }
                invoke(state.runId(), GraphInput.args(originalInput));
                return;
            }
            if (state.phase() == ShoppingAgentState.Phase.PREPARING_CONFIRMABLE_ORDER) {
                recoverPreparing(state, lastNode, config);
                return;
            }
            if (state.phase() == ShoppingAgentState.Phase.CREATING_ORDER) {
                recoverCreating(state, lastNode, config);
                return;
            }
            String stableNode = switch (state.phase()) {
                case NEEDS_CLARIFICATION -> "needClarification";
                case PRESENTING_CANDIDATES -> "presentCandidates";
                case WAITING_APPROVAL -> "awaitApproval";
                case NEEDS_CONSTRAINT_RELAXATION -> "constraintRelaxation";
                case COMPLETED -> "createOrder";
                case CANCELLED -> "cancel";
                default -> null;
            };
            if (stableNode != null && stableNode.equals(lastNode)) return;
            // 人工等待节点的默认边是 cancel/rejected。没有用户路由时不能裸 resume。
            if (isInterruptNode(lastNode)) return;
            graph.compiledGraph().invoke(GraphInput.resume(), config)
                    .orElseThrow(() -> new IllegalStateException("shopping graph returned no state"));
        } catch (RuntimeException runtime) {
            throw runtime;
        } catch (Exception checked) {
            throw new IllegalStateException("could not recover shopping graph", checked);
        }
    }

    private void recoverPreparing(ShoppingAgentState state, String lastNode, RunnableConfig config)
            throws Exception {
        if ("awaitApproval".equals(lastNode)) {
            // 过期批准把业务态翻成 PREPARING 后崩溃。必须走 requote，不能落到默认 rejected。
            RunnableConfig updated = graph.compiledGraph().updateState(config,
                    Map.of("approvalRoute", "requote"), lastNode);
            graph.compiledGraph().invoke(GraphInput.resume(), updated)
                    .orElseThrow(() -> new IllegalStateException("shopping graph returned no state"));
            return;
        }
        if ("recordSelection".equals(lastNode) || "prepareConfirmableOrder".equals(lastNode)) {
            graph.compiledGraph().invoke(GraphInput.resume(), config)
                    .orElseThrow(() -> new IllegalStateException("shopping graph returned no state"));
            return;
        }
        actions.prepareSelectedCandidate(state.runId(), state.userId());
    }

    private void recoverCreating(ShoppingAgentState state, String lastNode, RunnableConfig config)
            throws Exception {
        if (!"awaitApproval".equals(lastNode) || state.confirmableSnapshot() == null) return;
        RunnableConfig updated = graph.compiledGraph().updateState(config, Map.of(
                "approvalRoute", "approved",
                "snapshotId", state.confirmableSnapshot().snapshotId(),
                "summaryHash", state.confirmableSnapshot().summaryHash()), lastNode);
        graph.compiledGraph().invoke(GraphInput.resume(), updated)
                .orElseThrow(() -> new IllegalStateException("shopping graph returned no state"));
    }

    private static boolean isInterruptNode(String lastNode) {
        return "awaitApproval".equals(lastNode) || "needClarification".equals(lastNode)
                || "presentCandidates".equals(lastNode) || "constraintRelaxation".equals(lastNode);
    }

    private void resume(String runId, String expectedNode, Map<String, Object> userInput) {
        executionLock.execute(runId, () -> resumeLocked(runId, expectedNode, userInput));
    }

    private void resumeLocked(String runId, String expectedNode, Map<String, Object> userInput) {
        RunnableConfig config = RunnableConfig.builder().threadId(runId).build();
        try {
            String interruptedNode = graph.compiledGraph().getState(config).state()
                    .<String>value("lastNode")
                    .orElseThrow(() -> new IllegalStateException("checkpoint has no interrupted node"));
            if (!expectedNode.equals(interruptedNode)) {
                throw new RunStateConflictException("run is waiting at " + interruptedNode
                        + ", command requires " + expectedNode);
            }
            RunnableConfig updated = graph.compiledGraph().updateState(config, userInput, interruptedNode);
            graph.compiledGraph().invoke(GraphInput.resume(), updated)
                    .orElseThrow(() -> new IllegalStateException("shopping graph returned no state"));
        } catch (RuntimeException runtime) {
            throw runtime;
        } catch (Exception checked) {
            throw new IllegalStateException("could not resume shopping graph", checked);
        }
    }
}
