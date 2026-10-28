package com.buyforu.commerce.application;

import com.buyforu.commerce.port.model.CommerceModels.SearchRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogSearchSqlTest {
    @Test
    void appliesQueryBrandsAndAttributesBeforeLimit() {
        SearchRequest request = new SearchRequest("u-1", "想买一台轻薄本", "笔记本", null, null,
                List.of("Pine"), Map.of("memory", "16GB"), "addr-1", null, 1, 10, List.of("游戏本"));

        CatalogSearchSql.Query query = CatalogSearchSql.build(request,
                List.of("轻薄本", "轻薄"), 100, JsonMapper.builder().build());

        assertTrue(query.sql().contains("p.attributes @>"));
        assertTrue(query.sql().contains("<> ALL"));
        assertTrue(query.sql().contains("unnest"));
        assertTrue(query.sql().contains("terms(token)"));
        assertTrue(query.sql().contains("excluded(token)"));
        assertTrue(query.sql().indexOf("unnest") < query.sql().indexOf("LIMIT"));
    }
}
