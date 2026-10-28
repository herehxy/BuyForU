package com.buyforu.commerce.port;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogSearchTextTest {
    @Test
    void extractsUsefulCjkTermsFromShoppingSentence() {
        List<String> tokens = CatalogSearchText.tokens("想买一台轻薄本");

        assertTrue(tokens.contains("轻薄本"));
        assertTrue(tokens.contains("轻薄"));
        assertTrue(tokens.contains("薄本"));
        assertFalse(tokens.contains("想买"));
        assertFalse(tokens.contains("一台"));
    }

    @Test
    void removesStopwordsAroundCategoryAndBudget() {
        List<String> tokens = CatalogSearchText.tokens("5000元以内的笔记本");

        assertTrue(tokens.contains("笔记本"));
        assertTrue(tokens.contains("笔记"));
        assertTrue(tokens.contains("记本"));
        assertFalse(tokens.contains("以内"));
    }

    @Test
    void queryMatchingIsCaseInsensitiveAndUsesAnyUsefulTerm() {
        assertTrue(CatalogSearchText.matches("降噪耳机", "Hush Studio 耳机 anc=yes"));
        assertFalse(CatalogSearchText.matches("显示器", "Aurora Air 16 轻薄本"));
    }
}
