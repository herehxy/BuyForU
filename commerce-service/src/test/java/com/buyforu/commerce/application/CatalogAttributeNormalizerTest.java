package com.buyforu.commerce.application;

import com.buyforu.commerce.port.CatalogAttributeNormalizer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CatalogAttributeNormalizerTest {
    @Test
    void mapsModelMemoryAliasesOntoCatalogKeys() {
        assertEquals(Map.of("memory", "16GB"),
                CatalogAttributeNormalizer.skuAttributes(Map.of("memoryGB", "16")));
        assertEquals(Map.of("memory", "16GB"),
                CatalogAttributeNormalizer.skuAttributes(Map.of("memory", "16GB")));
        assertEquals(Map.of(), CatalogAttributeNormalizer.skuAttributes(Map.of("type", "轻薄本")));
    }

    @Test
    void normalizesBooleanLikeCatalogValues() {
        assertEquals(Map.of("anc", "yes", "wireless", "yes"),
                CatalogAttributeNormalizer.skuAttributes(Map.of(
                        "noiseCancellation", "主动降噪",
                        "wireless", "是")));
        assertEquals(Map.of("anc", "no"),
                CatalogAttributeNormalizer.skuAttributes(Map.of("降噪", "不支持")));
    }
}
