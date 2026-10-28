package com.buyforu.commerce.application;

import com.buyforu.commerce.port.CatalogAttributeNormalizer;
import com.buyforu.commerce.port.CatalogVocabulary;
import com.buyforu.commerce.port.model.CommerceModels.SearchRequest;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * SQL 过滤必须在 LIMIT 之前完成。查询词作为相关性条件参与 SQL，
 * 而不是先截断价格最低的候选再在 Java 里做一次整句 contains。
 */
final class CatalogSearchSql {
    private CatalogSearchSql() {
    }

    static Query build(SearchRequest request, List<String> queryTokens, int limit, ObjectMapper json) {
        String category = CatalogVocabulary.category(request.category());
        if (category == null && request.category() != null) category = request.category().trim();

        StringBuilder sql = new StringBuilder("""
                SELECT p.product_id, s.sku_id, p.name, p.brand, p.attributes::text,
                       s.unit_price, i.available_quantity
                FROM commerce_schema.product p
                JOIN commerce_schema.sku s ON s.product_id = p.product_id
                JOIN commerce_schema.inventory i ON i.sku_id = s.sku_id
                WHERE s.status = 'ACTIVE'
                  AND (? = '' OR lower(p.category) = lower(?))
                  AND i.available_quantity >= ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(category == null ? "" : category);
        args.add(category == null ? "" : category);
        args.add(request.quantity());

        if (!request.excludedBrands().isEmpty()) {
            sql.append("""
                      AND lower(p.brand) <> ALL (CAST(? AS text[]))
                    """);
            args.add(arrayLiteral(request.excludedBrands().stream()
                    .map(value -> value.toLowerCase(Locale.ROOT)).toList()));
        }
        if (!request.excludedTerms().isEmpty()) {
            sql.append("""
                      AND NOT EXISTS (
                          SELECT 1
                          FROM unnest(CAST(? AS text[])) AS excluded(token)
                          WHERE lower(concat_ws(' ', p.name, p.brand, p.category, p.attributes::text))
                                LIKE '%' || lower(token) || '%'
                      )
                    """);
            args.add(arrayLiteral(request.excludedTerms().stream()
                    .map(value -> value.toLowerCase(Locale.ROOT)).toList()));
        }
        var attributes = CatalogAttributeNormalizer.skuAttributes(request.requiredAttributes());
        if (!attributes.isEmpty()) {
            sql.append("""
                      AND p.attributes @> CAST(? AS jsonb)
                    """);
            args.add(json.writeValueAsString(attributes));
        }
        if (queryTokens != null && !queryTokens.isEmpty()) {
            sql.append("""
                      AND EXISTS (
                          SELECT 1
                          FROM unnest(CAST(? AS text[])) AS terms(token)
                          WHERE lower(concat_ws(' ', p.name, p.brand, p.category, p.attributes::text))
                                LIKE '%' || lower(token) || '%'
                      )
                    """);
            args.add(arrayLiteral(queryTokens.stream().map(value -> value.toLowerCase(Locale.ROOT)).toList()));
        }
        sql.append("""
                ORDER BY s.unit_price
                LIMIT ?
                """);
        args.add(limit);
        return new Query(sql.toString(), List.copyOf(args));
    }

    private static String arrayLiteral(List<String> values) {
        return values.stream()
                .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",", "{", "}"));
    }

    record Query(String sql, List<Object> args) {
    }
}
