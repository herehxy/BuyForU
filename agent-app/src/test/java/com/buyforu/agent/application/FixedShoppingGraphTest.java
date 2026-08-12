package com.buyforu.agent.application;

import org.bsc.langgraph4j.GraphRepresentation;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证固定图必须停在人工节点，并且只有显式 resume 输入才能继续。 */
class FixedShoppingGraphTest {
    @Test
    void pausesForApprovalAndResumesOnlyAfterExplicitInput() throws Exception {
        FixedShoppingGraph graph = new FixedShoppingGraph();
        RunnableConfig config = RunnableConfig.builder().threadId("run-1").build();

        // 无业务动作的测试图仍必须先停在候选展示，不能自动选择商品。
        var paused = graph.compiledGraph().invoke(GraphInput.args(Map.of()), config).orElseThrow();
        assertEquals("presentCandidates", paused.value("lastNode").orElseThrow());

        config = graph.compiledGraph().updateState(config, Map.of("selectionRoute", "selected"),
                "presentCandidates");
        var approval = graph.compiledGraph().invoke(GraphInput.resume(), config)
                .orElseThrow();
        assertEquals("awaitApproval", approval.value("lastNode").orElseThrow());

        config = graph.compiledGraph().updateState(config, Map.of("approvalRoute", "approved"),
                "awaitApproval");
        var completed = graph.compiledGraph().invoke(GraphInput.resume(), config)
                .orElseThrow();

        assertEquals("createOrder", completed.value("lastNode").orElseThrow());
        String mermaid = graph.compiledGraph()
                .getGraph(GraphRepresentation.Type.MERMAID).content();
        assertTrue(mermaid.contains("presentCandidates"));
        assertTrue(mermaid.contains("constraintRelaxation"));
    }
}
