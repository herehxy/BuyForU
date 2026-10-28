package com.buyforu.commerce.port;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将用户口语转成 Commerce 可检索的词项。
 *
 * <p>中文没有空格，直接按 {@code contains} 匹配整句会把“想买一台轻薄本”当成一个词。
 * 这里只做轻量、确定性的停用词清理和二元组展开，不引入模型或外部分词服务。</p>
 */
public final class CatalogSearchText {
    private static final int MAX_TOKENS = 12;
    private static final Pattern NEGATIVE_TERM = Pattern.compile(
            "(?:不要买|不想要|不要|别要|排除|拒绝|不需要|不含)([^，。；、\\s]+)");
    private static final Set<String> STOPWORDS = Set.of(
            "想买", "帮我", "一台", "一个", "预算", "以内", "以下", "不超过", "以上",
            "至少", "不低于", "明天", "后天", "今天", "这周", "周末", "能到", "送到",
            "用的", "需要", "来个", "只要", "买", "的");

    private CatalogSearchText() {
    }

    public static List<String> tokens(String query) {
        if (query == null || query.isBlank()) return List.of();
        String cleaned = removeNegativeTerms(query.toLowerCase(Locale.ROOT));
        // 价格/容量/刷新率不是商品名的一部分，先移除数值单位，避免把“5000元”当作品类词。
        cleaned = cleaned.replaceAll(
                "\\d+(?:\\.\\d+)?\\s*(?:元|块|万|千|gb|tb|mb|hz|dpi|英寸|寸)", " ");
        for (String stopword : STOPWORDS.stream().sorted((left, right) -> right.length() - left.length()).toList()) {
            cleaned = cleaned.replace(stopword, " ");
        }

        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String raw : cleaned.split("[^\\p{IsAlphabetic}\\p{IsDigit}]+")) {
            String token = raw.trim();
            if (token.length() < 2 || token.matches("\\d+")) continue;
            if (containsCjk(token)) {
                result.add(token);
                for (int index = 0; index + 1 < token.length(); index++) {
                    result.add(token.substring(index, index + 2));
                }
            } else {
                result.add(token);
            }
            if (result.size() >= MAX_TOKENS) break;
        }
        return List.copyOf(result);
    }

    public static boolean matches(String query, String text) {
        List<String> tokens = tokens(query);
        if (tokens.isEmpty()) return true;
        String haystack = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return tokens.stream().anyMatch(haystack::contains);
    }

    /** 从“不想/不要/排除”等短语中提取需要硬排除的商品词。 */
    public static List<String> excludedTerms(String query) {
        if (query == null || query.isBlank()) return List.of();
        Matcher matcher = NEGATIVE_TERM.matcher(query);
        LinkedHashSet<String> excluded = new LinkedHashSet<>();
        while (matcher.find()) {
            String term = matcher.group(1).replaceAll("(?:的|品牌)$", "").trim();
            if (term.length() >= 2) excluded.add(term);
        }
        return List.copyOf(excluded);
    }

    private static String removeNegativeTerms(String query) {
        return NEGATIVE_TERM.matcher(query).replaceAll(" ");
    }

    private static boolean containsCjk(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint)
                == Character.UnicodeScript.HAN);
    }
}
