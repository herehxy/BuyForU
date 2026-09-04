package com.buyforu.agent.eval;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Golden Set 先保证评估数据能读。真正的向量召回需要 pgvector，放在 IT 里。 */
class RagRetrievalEvalTest {
    @Test
    void goldenSetListsQueryAndExpectedDocuments() throws Exception {
        Path file = Path.of("eval/rag/golden-set.json");
        if (!Files.exists(file)) file = Path.of("../eval/rag/golden-set.json");
        JsonNode root = JsonMapper.builder().build().readTree(Files.readString(file));
        assertTrue(root.isArray());
        assertFalse(root.isEmpty());
        for (JsonNode item : root) {
            assertFalse(item.get("query").asString().isBlank());
            assertTrue(item.get("mustIncludeDocumentIds").size() > 0);
        }
    }
}
