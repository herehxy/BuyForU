package com.buyforu.commerce.port;

import java.util.List;
import java.util.Locale;

/** 目录品类词的唯一归一化入口，避免 JDBC 查询和内存实现各自维护别名。 */
public final class CatalogVocabulary {
    private static final List<Alias> CATEGORIES = List.of(
            new Alias("laptop", "laptop", "notebook", "电脑", "笔记本", "轻薄本", "游戏本", "商务本", "性能本", "创作本"),
            new Alias("phone", "phone", "smartphone", "手机"),
            new Alias("headphone", "headphone", "headphones", "earphone", "耳机"),
            new Alias("tablet", "tablet", "平板", "平板电脑"),
            new Alias("keyboard", "keyboard", "键盘"),
            new Alias("mouse", "mouse", "鼠标"),
            new Alias("monitor", "monitor", "display", "显示器")
    );

    private CatalogVocabulary() {
    }

    public static String category(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        String best = null;
        int bestLength = 0;
        for (Alias alias : CATEGORIES) {
            for (String candidate : alias.values()) {
                if (normalized.contains(candidate) && candidate.length() > bestLength) {
                    best = alias.canonical();
                    bestLength = candidate.length();
                }
            }
        }
        return best;
    }

    private record Alias(String canonical, String... values) {
    }
}
