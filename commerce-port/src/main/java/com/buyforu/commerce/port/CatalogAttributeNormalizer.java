package com.buyforu.commerce.port;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把规划模型随口起的规格名收成目录里的键。
 * DeepSeek 常写出 memoryGB=16，商品表存的是 memory=16GB，过滤和排序必须用同一套键。
 */
public final class CatalogAttributeNormalizer {
    private static final Set<String> PLANNING_ONLY = Set.of("type", "category", "品类");

    private CatalogAttributeNormalizer() {
    }

    public static Map<String, String> skuAttributes(Map<String, String> required) {
        if (required == null || required.isEmpty()) return Map.of();
        Map<String, String> sku = new LinkedHashMap<>();
        required.forEach((key, value) -> {
            if (key == null || PLANNING_ONLY.contains(key.toLowerCase(Locale.ROOT))) return;
            String canonical = canonicalKey(key);
            String normalized = canonical.equals("memory") || canonical.equals("storage")
                    ? normalizeCapacity(value) : value;
            if (normalized != null && !normalized.isBlank()) sku.put(canonical, value == null ? null : normalized);
        });
        return sku;
    }

    private static String canonicalKey(String key) {
        String k = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return switch (k) {
            case "memory", "memorygb", "ram", "ramgb", "内存" -> "memory";
            case "storage", "storagegb", "ssd", "disk", "容量" -> "storage";
            default -> key;
        };
    }

    private static String normalizeCapacity(String value) {
        if (value == null || value.isBlank()) return value;
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return value.trim();
        String upper = value.toUpperCase(Locale.ROOT);
        String unit = upper.contains("TB") ? "TB" : "GB";
        return digits + unit;
    }
}
