package com.buyforu.commerce.application;

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
}
