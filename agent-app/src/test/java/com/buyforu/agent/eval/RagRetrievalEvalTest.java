package com.buyforu.agent.eval;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 金标集只是评测数据，本类不碰向量库——真实召回需要 pgvector + Ollama，放在
 * {@link RagRetrievalEvalIT} 里。本类跑得快、无外部依赖，可以每次都跑。
 *
 * 这里守的是三件容易腐坏的事：
 * 1. 格式：正样本要有期望文档，负样本必须没有期望文档（否则负样本形同虚设）。
 * 2. 覆盖：语料目录里每篇文档都得被至少一条正样本指到，否则那篇文档其实从没被测过。
 * 3. 契约：金标集里的 topK / threshold 必须等于生产调用点 {@code SpringAiPlanningModel} 的字面量。
 *    两处是同一份契约的两次书写，漂移之后评测会在错误的阈值下"通过"，比失败更危险。
 */
class RagRetrievalEvalTest {

    private static final Pattern RETRIEVE_CALL =
            Pattern.compile("knowledge\\s*\\.\\s*retrieve\\s*\\([^,]+,\\s*(\\d+)\\s*,\\s*([0-9.]+)\\s*\\)");

    @Test
    void goldenSetDeclaresRetrievalContract() {
        JsonNode root = RagEvalFixtures.goldenSet();
        assertTrue(root.isObject(), "金标集顶层应为对象（含 retrieval 契约），不再是裸数组");
        assertTrue(root.get("version").asInt() >= 2, "缺少 version，无法判断格式代次");

        JsonNode retrieval = root.get("retrieval");
        assertNotNull(retrieval, "缺少 retrieval 块，评测参数就没有唯一出处");
        assertTrue(retrieval.get("topK").asInt() > 0, "topK 必须为正");
        double threshold = retrieval.get("threshold").asDouble();
        assertTrue(threshold > 0 && threshold < 1, "threshold 应落在 (0,1)，否则要么全召回要么全不召回");
        assertFalse(retrieval.get("note").asString().isBlank(),
                "阈值必须写明标定依据；一个没有出处的常数迟早会被当成魔法数字改掉");
    }

    @Test
    void everySampleIsWellFormedForItsKind() {
        JsonNode root = RagEvalFixtures.goldenSet();
        Set<String> seenIds = new LinkedHashSet<>();
        List<String> corpusDocuments = RagEvalFixtures.corpusDocumentIds();
        assertFalse(corpusDocuments.isEmpty(), "语料目录下没有 .md 文档，评测无从谈起");

        JsonNode samples = root.get("samples");
        assertNotNull(samples, "缺少 samples");
        assertTrue(samples.size() >= 8, "样本数应覆盖三篇文档的正负两向，当前 " + samples.size());

        for (JsonNode sample : samples) {
            String id = sample.get("id").asString();
            assertFalse(id.isBlank(), "样本 id 不能为空");
            assertTrue(seenIds.add(id), "样本 id 重复：" + id);
            assertFalse(sample.get("query").asString().isBlank(), id + " 的 query 不能为空");

            String kind = sample.get("kind").asString();
            List<String> expected = textValues(sample.get("expectedDocumentIds"));

            switch (kind) {
                case "positive" -> {
                    assertFalse(expected.isEmpty(), id + " 是正样本却没有期望文档");
                    assertTrue(sample.get("minHits").asInt() >= 1, id + " 正样本的 minHits 至少为 1");
                    for (String document : expected) {
                        assertTrue(corpusDocuments.contains(document),
                                id + " 引用了语料目录里不存在的文档：" + document);
                    }
                }
                case "negative" -> {
                    assertTrue(expected.isEmpty(), id + " 是负样本，不应声明期望文档");
                    assertEquals(0, sample.get("maxHits").asInt(),
                            id + " 负样本的 maxHits 必须为 0，否则它其实是被允许命中的");
                }
                default -> throw new AssertionError(id + " 的 kind 只能是 positive 或 negative，实际：" + kind);
            }
        }
    }

    @Test
    void everyCorpusDocumentIsExercisedBySomePositiveSample() {
        Set<String> referenced = new LinkedHashSet<>();
        for (JsonNode sample : RagEvalFixtures.goldenSet().get("samples")) {
            if (!"positive".equals(sample.get("kind").asString())) continue;
            referenced.addAll(textValues(sample.get("expectedDocumentIds")));
        }

        List<String> orphans = new ArrayList<>();
        for (String document : RagEvalFixtures.corpusDocumentIds()) {
            if (!referenced.contains(document)) orphans.add(document);
        }
        assertTrue(orphans.isEmpty(), "这些语料文档没有任何正样本指向它们，等于从未被评测：" + orphans);
    }

    @Test
    void corpusDirectoryHoldsEveryReferencedDocument() {
        Set<String> onDisk = new LinkedHashSet<>(RagEvalFixtures.corpusDocumentIds());
        for (JsonNode sample : RagEvalFixtures.goldenSet().get("samples")) {
            for (String document : textValues(sample.get("expectedDocumentIds"))) {
                assertTrue(onDisk.contains(document),
                        sample.get("id").asString() + " 引用了语料目录里不存在的文档：" + document);
            }
        }
    }

    @Test
    void retrievalContractMatchesProductionCallSite() throws Exception {
        JsonNode retrieval = RagEvalFixtures.goldenSet().get("retrieval");

        Matcher matcher = RETRIEVE_CALL.matcher(Files.readString(RagEvalFixtures.planningModelPath()));
        assertTrue(matcher.find(),
                "在 SpringAiPlanningModel 里找不到 knowledge.retrieve(...) 调用，契约断言失效，请更新本测试");

        assertEquals(Integer.parseInt(matcher.group(1)), retrieval.get("topK").asInt(),
                "金标集的 topK 与生产调用点不一致，评测测的不是线上行为");
        assertEquals(Double.parseDouble(matcher.group(2)), retrieval.get("threshold").asDouble(),
                "金标集的 threshold 与生产调用点不一致，评测会在错误阈值下给出结论");
    }

    private static List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array == null || !array.isArray()) return values;
        for (JsonNode element : array) {
            values.add(element.asString());
        }
        return values;
    }
}
