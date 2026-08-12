package com.buyforu.agent.application;

import com.buyforu.agent.domain.PlanSpec.ShoppingConstraints;
import com.buyforu.agent.domain.ShoppingAgentState;
import com.buyforu.agent.domain.ShoppingAgentState.Phase;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.GraphRepresentation;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 固定且可恢复的购物状态图。
 * LLM 只提供 PlanSpec 数据，永远不能修改拓扑、插入写节点或绕过 awaitApproval。
 */
@Component
public final class FixedShoppingGraph {
    private final Map<Phase, Set<Phase>> edges = new EnumMap<>(Phase.class);
    private final CompiledGraph<GraphState> compiledGraph;
    private final ShoppingWorkflowService actions;
    private final ObjectMapper json;

    /** Unit-test graph with no external actions. */
    public FixedShoppingGraph() {
        this(new MemorySaver(), null, JsonMapper.builder().findAndAddModules().build());
    }

    @Autowired
    public FixedShoppingGraph(BaseCheckpointSaver checkpointSaver, ShoppingWorkflowService actions,
                              ObjectMapper json) {
        this.actions = actions;
        this.json = json;
        allow(Phase.NEW, Phase.NEEDS_CLARIFICATION, Phase.SEARCHING);
        allow(Phase.NEEDS_CLARIFICATION, Phase.SEARCHING, Phase.CANCELLED);
        allow(Phase.SEARCHING, Phase.PRESENTING_CANDIDATES, Phase.NEEDS_CONSTRAINT_RELAXATION, Phase.FAILED);
        allow(Phase.PRESENTING_CANDIDATES, Phase.PREPARING_CONFIRMABLE_ORDER, Phase.CANCELLED);
        allow(Phase.PREPARING_CONFIRMABLE_ORDER, Phase.WAITING_APPROVAL, Phase.SEARCHING,
                Phase.NEEDS_CONSTRAINT_RELAXATION, Phase.FAILED);
        allow(Phase.WAITING_APPROVAL, Phase.CREATING_ORDER, Phase.PREPARING_CONFIRMABLE_ORDER, Phase.CANCELLED);
        allow(Phase.CREATING_ORDER, Phase.COMPLETED, Phase.PREPARING_CONFIRMABLE_ORDER, Phase.FAILED);
        allow(Phase.NEEDS_CONSTRAINT_RELAXATION, Phase.SEARCHING, Phase.CANCELLED);
        compiledGraph = compile(checkpointSaver);
    }

    public boolean permits(Phase from, Phase to) {
        return edges.getOrDefault(from, Set.of()).contains(to);
    }

    public Map<Phase, Set<Phase>> topology() {
        return Map.copyOf(edges);
    }

    public CompiledGraph<GraphState> compiledGraph() {
        return compiledGraph;
    }

    public String mermaid() {
        return compiledGraph.getGraph(GraphRepresentation.Type.MERMAID).content();
    }

    private void allow(Phase from, Phase... to) {
        edges.put(from, EnumSet.copyOf(java.util.List.of(to)));
    }

    private CompiledGraph<GraphState> compile(BaseCheckpointSaver saver) {
        try {
            // 人工等待节点使用 interruptAfter。恢复时只能从对应节点携带用户命令继续。
            StateGraph<GraphState> graph = new StateGraph<>(GraphState::new);
            node(graph, "planSpec", this::plan);
            node(graph, "needClarification", state -> Map.of("lastNode", "needClarification"));
            node(graph, "applyClarification", this::clarify);
            node(graph, "searchAndRank", this::search);
            node(graph, "presentCandidates", state -> Map.of("lastNode", "presentCandidates"));
            node(graph, "recordSelection", this::select);
            node(graph, "prepareConfirmableOrder", this::prepare);
            node(graph, "awaitApproval", state -> Map.of("lastNode", "awaitApproval"));
            node(graph, "createOrder", this::createOrder);
            node(graph, "constraintRelaxation", state -> Map.of("lastNode", "constraintRelaxation"));
            node(graph, "applyConstraintRelaxation", this::relax);
            node(graph, "cancel", this::cancel);

            graph.addEdge(GraphDefinition.START, "planSpec")
                    .addConditionalEdges("planSpec", route("phaseRoute", "search"), Map.of(
                            "clarify", "needClarification", "search", "searchAndRank",
                            "present", "presentCandidates", "approval", "awaitApproval",
                            "relax", "constraintRelaxation", "completed", GraphDefinition.END,
                            "cancelled", GraphDefinition.END))
                    .addConditionalEdges("needClarification", route("clarificationRoute", "cancel"), Map.of(
                            "provided", "applyClarification", "cancel", "cancel"))
                    .addConditionalEdges("applyClarification", route("phaseRoute", "search"), Map.of(
                            "clarify", "needClarification", "search", "searchAndRank"))
                    .addConditionalEdges("searchAndRank", route("searchRoute", "present"), Map.of(
                            "present", "presentCandidates", "relax", "constraintRelaxation"))
                    .addConditionalEdges("presentCandidates", route("selectionRoute", "cancel"), Map.of(
                            "selected", "recordSelection", "cancel", "cancel"))
                    .addEdge("recordSelection", "prepareConfirmableOrder")
                    .addConditionalEdges("prepareConfirmableOrder", route("prepareRoute", "approval"), Map.of(
                            "approval", "awaitApproval", "present", "presentCandidates",
                            "relax", "constraintRelaxation"))
                    .addConditionalEdges("awaitApproval", route("approvalRoute", "rejected"), Map.of(
                            "approved", "createOrder", "requote", "prepareConfirmableOrder",
                            "rejected", "cancel"))
                    .addConditionalEdges("constraintRelaxation", route("relaxationRoute", "cancel"), Map.of(
                            "approved", "applyConstraintRelaxation", "cancel", "cancel"))
                    .addEdge("applyConstraintRelaxation", "searchAndRank")
                    .addConditionalEdges("createOrder", route("orderRoute", "completed"), Map.of(
                            "completed", GraphDefinition.END, "requote", "awaitApproval"))
                    .addEdge("cancel", GraphDefinition.END);
            return graph.compile(org.bsc.langgraph4j.CompileConfig.builder()
                    .graphId("buyforu-shopping-v1")
                    .checkpointSaver(saver)
                    .interruptAfter("needClarification", "presentCandidates", "awaitApproval", "constraintRelaxation")
                    .releaseThread(false)
                    .build());
        } catch (Exception exception) {
            throw new IllegalStateException("Canonical shopping graph is invalid", exception);
        }
    }

    private Map<String, Object> plan(GraphState graph) {
        if (actions == null) return Map.of("lastNode", "planSpec", "phaseRoute", "search");
        ShoppingAgentState state = actions.planNewRun(required(graph, "runId"), required(graph, "traceId"),
                required(graph, "conversationId"), required(graph, "userId"), required(graph, "request"),
                graph.<String>value("constraintsJson")
                        .map(value -> json.readValue(value, ShoppingConstraints.class)).orElse(null));
        // 若业务状态已保存但图 checkpoint 尚未保存，可以从持久化 phase 重建正确路由。
        String route = switch (state.phase()) {
            case NEEDS_CLARIFICATION -> "clarify";
            case SEARCHING, NEW -> "search";
            case PRESENTING_CANDIDATES -> "present";
            case WAITING_APPROVAL -> "approval";
            case NEEDS_CONSTRAINT_RELAXATION -> "relax";
            case COMPLETED -> "completed";
            case CANCELLED -> "cancelled";
            default -> throw new RunStateConflictException(
                    "transitional run cannot be reconstructed from the graph start node: " + state.phase());
        };
        return stateUpdate("planSpec", state, "phaseRoute", route);
    }

    private Map<String, Object> clarify(GraphState graph) {
        ShoppingAgentState state = requireActions().applyClarification(required(graph, "runId"),
                required(graph, "userId"), required(graph, "clarificationMessage"));
        return stateUpdate("applyClarification", state, "phaseRoute",
                state.phase() == Phase.NEEDS_CLARIFICATION ? "clarify" : "search");
    }

    private Map<String, Object> search(GraphState graph) {
        if (actions == null) return Map.of("lastNode", "searchAndRank", "searchRoute", "present");
        ShoppingAgentState state = actions.search(required(graph, "runId"), required(graph, "userId"));
        return stateUpdate("searchAndRank", state, "searchRoute",
                state.phase() == Phase.PRESENTING_CANDIDATES ? "present" : "relax");
    }

    private Map<String, Object> select(GraphState graph) {
        if (actions == null) return Map.of("lastNode", "recordSelection");
        ShoppingAgentState state = requireActions().recordCandidateSelection(required(graph, "runId"),
                required(graph, "userId"), required(graph, "selectedSkuId"));
        return stateUpdate("recordSelection", state, null, null);
    }

    private Map<String, Object> prepare(GraphState graph) {
        if (actions == null) return Map.of("lastNode", "prepareConfirmableOrder", "prepareRoute", "approval");
        ShoppingAgentState state = actions.prepareSelectedCandidate(required(graph, "runId"), required(graph, "userId"));
        String route = switch (state.phase()) {
            case WAITING_APPROVAL -> "approval";
            case PRESENTING_CANDIDATES, PREPARING_CONFIRMABLE_ORDER -> "present";
            default -> "relax";
        };
        return stateUpdate("prepareConfirmableOrder", state, "prepareRoute", route);
    }

    private Map<String, Object> createOrder(GraphState graph) {
        if (actions == null) return Map.of("lastNode", "createOrder", "orderRoute", "completed");
        ShoppingAgentState state = actions.approve(required(graph, "runId"), required(graph, "userId"),
                required(graph, "snapshotId"), required(graph, "summaryHash"));
        return stateUpdate("createOrder", state, "orderRoute",
                state.phase() == Phase.COMPLETED ? "completed" : "requote");
    }

    private Map<String, Object> relax(GraphState graph) {
        ShoppingAgentState state = requireActions().applyConstraintRelaxation(required(graph, "runId"),
                required(graph, "userId"), required(graph, "relaxationMessage"));
        return stateUpdate("applyConstraintRelaxation", state, null, null);
    }

    private Map<String, Object> cancel(GraphState graph) {
        if (actions == null) return Map.of("lastNode", "cancel");
        ShoppingAgentState state = actions.cancel(required(graph, "runId"), required(graph, "userId"));
        return stateUpdate("cancel", state, null, null);
    }

    private ShoppingWorkflowService requireActions() {
        if (actions == null) throw new IllegalStateException("graph actions are not configured");
        return actions;
    }

    private static Map<String, Object> stateUpdate(String node, ShoppingAgentState state,
                                                   String routeKey, String route) {
        Map<String, Object> update = new HashMap<>();
        update.put("lastNode", node);
        update.put("businessPhase", state.phase().name());
        if (routeKey != null) update.put(routeKey, route);
        return update;
    }

    private static String required(GraphState state, String key) {
        return state.<String>value(key).filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("graph input is missing " + key));
    }

    private void node(StateGraph<GraphState> graph, String name,
                      Function<GraphState, Map<String, Object>> action) throws Exception {
        graph.addNode(name, AsyncNodeAction.node_async(action::apply));
    }

    private AsyncEdgeAction<GraphState> route(String key, String fallback) {
        return AsyncEdgeAction.edge_async(state -> state.<String>value(key).orElse(fallback));
    }

    public static final class GraphState extends AgentState {
        public GraphState(Map<String, Object> data) {
            super(new HashMap<>(data));
        }
    }
}
