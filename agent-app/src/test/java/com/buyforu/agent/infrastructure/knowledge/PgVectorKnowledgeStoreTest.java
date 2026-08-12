package com.buyforu.agent.infrastructure.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证知识切块不会超过 Embedding 输入边界，并拒绝空语料。 */
class PgVectorKnowledgeStoreTest {
    @Test
    void chunksOnParagraphBoundariesWithinEmbeddingLimit() {
        String first = "a".repeat(700);
        String second = "b".repeat(700);

        var chunks = PgVectorKnowledgeStore.chunk(first + "\n\n" + second);

        assertEquals(2, chunks.size());
        assertTrue(chunks.stream().allMatch(value -> value.length() <= 1200));
    }

    @Test
    void rejectsEmptyKnowledge() {
        assertThrows(IllegalArgumentException.class, () -> PgVectorKnowledgeStore.chunk(" \n "));
    }
}
