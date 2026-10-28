package com.buyforu.commerce.port;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CatalogVocabularyTest {
    @Test
    void normalizesEverySeededCatalogCategory() {
        assertEquals("laptop", CatalogVocabulary.category("笔记本"));
        assertEquals("phone", CatalogVocabulary.category("手机"));
        assertEquals("headphone", CatalogVocabulary.category("耳机"));
        assertEquals("tablet", CatalogVocabulary.category("平板电脑"));
        assertEquals("keyboard", CatalogVocabulary.category("机械键盘"));
        assertEquals("mouse", CatalogVocabulary.category("鼠标"));
        assertEquals("monitor", CatalogVocabulary.category("显示器"));
    }

    @Test
    void leavesUnknownCategoryUnknown() {
        assertNull(CatalogVocabulary.category("咖啡机"));
    }
}
