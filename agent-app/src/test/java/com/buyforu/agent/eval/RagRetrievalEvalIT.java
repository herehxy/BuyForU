package com.buyforu.agent.eval;

import com.buyforu.agent.application.KnowledgeRetriever.KnowledgeHit;
import com.buyforu.agent.concurrency.ConcurrencyProperties;
import com.buyforu.agent.concurrency.DependencyExecutor;
import com.buyforu.agent.concurrency.InFlightCallRegistry;
import com.buyforu.agent.infrastructure.knowledge.PgVectorKnowledgeStore;
import com.buyforu.agent.it.PostgresSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实召回评测：干净容器库 + 自动灌库 + 真实嵌入，跑 {@code golden-set.json} 的样本。
 *
 * 为什么必须用真实嵌入：本类要守的核心性质是"阈值可达"。用哈希或词袋造假的嵌入模型，
 * 分数分布完全是另一回事，阈值是否可达就无从谈起——那样的评测只会恒绿。
 *
 * 因此本类需要一个可达的 Ollama；不可达时**跳过**而不是失败（跳过是显式的，
 * 目的是避免"没跑"被误读成"通过"）。
 *
 * 相似度矩阵在 {@link BeforeAll} 里算一次并打印，各断言共用，避免重复调用嵌入服务。
 */
@Testcontainers(disabledWithoutDocker = true)
class RagRetrievalEvalIT {

    private static final String OLLAMA_BASE_URL =
            System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresSupport.postgres();

    private static JsonNode goldenSet;
    private static int topK;
    private static double threshold;
    private static List<RagEvalFixtures.IngestedDocument> ingested;
    /** sampleId -> 该样本下全部文档的得分（已按分数降序，未经阈值过滤）。 */
    private static Map<String, List<KnowledgeHit>> matrix;

    @BeforeAll
    static void ingestAndMeasure() {
        DirectOllamaEmbeddingModel embedding =
                new DirectOllamaEmbeddingModel(OLLAMA_BASE_URL, RagEvalFixtures.embeddingModelName());
        assumeTrue(embedding.reachable(),
                "本机 Ollama 不可达（" + OLLAMA_BASE_URL + "），无法做真实召回评测，跳过");

        DataSource dataSource = PostgresSupport.dataSource(POSTGRES);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        PgVectorKnowledgeStore store = new PgVectorKnowledgeStore(jdbc, embedding,
                JsonMapper.builder().build(), RagEvalFixtures.embeddingModelName(),
                new DependencyExecutor(concurrency(), meters, new InFlightCallRegistry(meters)),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));

        ingested = RagEvalFixtures.ingestCorpus(store, "rag-eval-fixture");

        goldenSet = RagEvalFixtures.goldenSet();
        topK = goldenSet.get("retrieval").get("topK").asInt();
        threshold = goldenSet.get("retrieval").get("threshold").asDouble();

        // 用 -1.0 作下限取出全部候选，这样打印的是完整分数矩阵而不只是过线结果——
        // 诊断"为什么没召回"时，看到 0.51 和看到 0.31 是完全不同的两件事。
        matrix = new LinkedHashMap<>();
        for (JsonNode sample : goldenSet.get("samples")) {
            matrix.put(sample.get("id").asString(),
                    store.retrieve(sample.get("query").asString(), topK, -1.0));
        }

        printMatrix();
    }

    private static void printMatrix() {
        System.out.printf("%n=== RAG 召回评测（嵌入模型 %s，topK %d，阈值 %.2f）===%n",
                RagEvalFixtures.embeddingModelName(), topK, threshold);
        System.out.printf("灌库：%d 篇语料，分块共 %d 个%n", ingested.size(),
                ingested.stream().mapToInt(RagEvalFixtures.IngestedDocument::chunkCount).sum());
        System.out.printf("%-26s%-10s%s%n", "样本", "kind", "各文档得分（按降序）");
        for (JsonNode sample : goldenSet.get("samples")) {
            String sampleId = sample.get("id").asString();
            StringBuilder line = new StringBuilder();
            for (KnowledgeHit hit : matrix.get(sampleId)) {
                line.append(String.format("%s %.4f%s  ", shortName(hit.documentId()), hit.score(),
                        hit.score() >= threshold ? "*" : ""));
            }
            System.out.printf("%-26s%-10s%s%n", sampleId, sample.get("kind").asString(), line);
        }
        System.out.printf("（* 表示达到阈值 %.2f）%n%n", threshold);
    }

    private static String shortName(String documentId) {
        return documentId.replace("policy-", "");
    }

    private static int positives() {
        return countSamples("positive");
    }

    private static int countSamples(String kind) {
        int total = 0;
        for (JsonNode sample : goldenSet.get("samples")) {
            if (kind.equals(sample.get("kind").asString())) total++;
        }
        return total;
    }

    /** 过线命中：按真实阈值过滤后取前 topK。 */
    private static List<String> hits(String sampleId) {
        List<String> hits = new ArrayList<>();
        for (KnowledgeHit hit : matrix.get(sampleId)) {
            if (hit.score() >= threshold && hits.size() < topK) hits.add(hit.documentId());
        }
        return hits;
    }

    /** 召回成功的正样本数：期望文档全部出现在过线结果里。 */
    private static int recoveredPositives() {
        int recovered = 0;
        for (JsonNode sample : goldenSet.get("samples")) {
            if (!"positive".equals(sample.get("kind").asString())) continue;
            List<String> hits = hits(sample.get("id").asString());
            boolean all = true;
            for (JsonNode expected : sample.get("expectedDocumentIds")) {
                if (!hits.contains(expected.asString())) all = false;
            }
            if (all) recovered++;
        }
        return recovered;
    }

    /** 排序质量：不看阈值，只看第一名是否落在期望文档里。 */
    private static int topOneHits() {
        int matched = 0;
        for (JsonNode sample : goldenSet.get("samples")) {
            if (!"positive".equals(sample.get("kind").asString())) continue;
            List<KnowledgeHit> ranked = matrix.get(sample.get("id").asString());
            if (ranked.isEmpty()) continue;
            String best = ranked.getFirst().documentId();
            for (JsonNode expected : sample.get("expectedDocumentIds")) {
                if (expected.asString().equals(best)) matched++;
            }
        }
        return matched;
    }

    private static int falsePositiveNegatives() {
        int fired = 0;
        for (JsonNode sample : goldenSet.get("samples")) {
            if (!"negative".equals(sample.get("kind").asString())) continue;
            if (!hits(sample.get("id").asString()).isEmpty()) fired++;
        }
        return fired;
    }

    @Test
    void everyCorpusDocumentIsVectorised() {
        int vectorised = new JdbcTemplate(PostgresSupport.dataSource(POSTGRES)).queryForObject("""
                SELECT count(*) FROM agent_schema.knowledge_document d
                WHERE d.embedding_model = ?
                  AND EXISTS (SELECT 1 FROM agent_schema.knowledge_chunk c
                              WHERE c.document_id = d.document_id AND c.embedding IS NOT NULL)
                """, Integer.class, RagEvalFixtures.embeddingModelName());
        assertEquals(ingested.size(), vectorised,
                "有语料文档没有落成向量，检索时会被 embedding_model 条件静默排除");
    }

    /**
     * 本类存在的首要理由：阈值必须可达。
     *
     * 曾经的线上阈值 0.65 在当前嵌入模型与切块粒度下不可达（实际天花板约 0.62），
     * 导致 retrieve() 对**任何**查询都返回空——RAG 接好了线却永远是死的，而且不报错。
     * 这条断言就是为了让那种情况无法再次悄悄发生。
     */
    @Test
    void thresholdIsReachable() {
        int recovered = recoveredPositives();
        assertTrue(recovered >= minimumAcceptable(),
                "阈值 " + threshold + " 下只有 " + recovered + "/" + positives()
                        + " 条正样本召回成功，接近'召回恒为空'。阈值需要按评测集重新标定");
    }

    /** 召回错误的政策数字比不召回更危险：订单按真实规则成交，但话术用的是错文档。 */
    @Test
    void negativesStayBelowThreshold() {
        assertEquals(0, falsePositiveNegatives(),
                "负样本在阈值 " + threshold + " 下被召回，说明阈值偏低，会喂给模型错误的政策依据");
    }

    @Test
    void rankingBeatsRandomGuessing() {
        int matched = topOneHits();
        assertTrue(matched >= minimumAcceptable(),
                "正样本 top1 命中 " + matched + "/" + positives() + "，未明显优于随机（1/" + ingested.size() + "）");
    }

    private static int minimumAcceptable() {
        return (positives() + 1) / 2;
    }

    /**
     * 本评测只用到 embeddingConcurrency，其余容量参数取与评测无关的最小合法值——
     * 它们影响的是并发治理，不是召回质量。
     */
    private static ConcurrencyProperties concurrency() {
        return new ConcurrencyProperties("rag-eval", Duration.ofSeconds(30), Duration.ofSeconds(10),
                1, 1, 1, 1, 1, 1, 4,
                4, 4, 4, 60, 4, 60, 4, 60, 4, 50, 50);
    }
}
