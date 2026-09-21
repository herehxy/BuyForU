package com.buyforu.agent.eval;

import com.buyforu.agent.infrastructure.knowledge.PgVectorKnowledgeStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * RAG 评测的共享夹具：读金标集、列语料、自动灌库。
 *
 * 语料清单**从目录推导**，不在金标集里维护第二份副本——副本一定会漂移，
 * 而"某篇文档其实从没被评测过"这种问题是不会自己暴露的。
 *
 * 灌库走的是生产同一条路径 {@code PgVectorKnowledgeStore.index(...)}，
 * 不写 SQL 直插——否则评测通过只证明 SQL 写对了，证明不了被测代码能读回来。
 */
final class RagEvalFixtures {

    /** 与手工灌库脚本保持一致，使两种灌库渠道产出的记录可以互换。 */
    private static final String SOURCE_URI_PREFIX =
            "https://github.com/herehxy/BuyForU/blob/main/eval/rag/corpus/";
    private static final String CORPUS_VERSION = "1.0.0";

    private RagEvalFixtures() { }

    static Path goldenSetPath() {
        return firstExisting(Path.of("eval/rag/golden-set.json"), Path.of("../eval/rag/golden-set.json"));
    }

    static Path planningModelPath() {
        return firstExisting(
                Path.of("src/main/java/com/buyforu/agent/application/SpringAiPlanningModel.java"),
                Path.of("agent-app/src/main/java/com/buyforu/agent/application/SpringAiPlanningModel.java"));
    }

    private static Path applicationYmlPath() {
        return firstExisting(
                Path.of("src/main/resources/application.yml"),
                Path.of("agent-app/src/main/resources/application.yml"));
    }

    /**
     * 嵌入模型名必须与生产配置一致：换模型等于换向量空间，分数分布完全不可比。
     * 从 application.yml 里取默认值，而不是在评测里另写一个常量——否则就是第二份真相。
     */
    static String embeddingModelName() {
        String yaml = read(applicationYmlPath());
        var matcher = Pattern.compile("model:\\s*\\$\\{OLLAMA_EMBEDDING_MODEL:([^}]+)}").matcher(yaml);
        if (!matcher.find()) {
            throw new IllegalStateException("application.yml 里找不到嵌入模型配置，本评测的防漂移前提失效");
        }
        return matcher.group(1).trim();
    }

    private static Path firstExisting(Path... candidates) {
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("找不到评测文件，检查测试的工作目录");
    }

    static JsonNode goldenSet() {
        try {
            return JsonMapper.builder().build().readTree(Files.readString(goldenSetPath()));
        } catch (Exception failure) {
            throw new IllegalStateException("金标集读取失败：" + goldenSetPath(), failure);
        }
    }

    static Path corpusDirectory() {
        return goldenSetPath().getParent().resolve(goldenSet().get("corpusDir").asString());
    }

    /** 语料文件按名字排序；README 是说明文档而非语料，排除。 */
    static List<Path> corpusFiles() {
        Path directory = corpusDirectory();
        try (var entries = Files.list(directory)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .filter(path -> !"README.md".equals(path.getFileName().toString()))
                    .sorted()
                    .toList();
        } catch (Exception failure) {
            throw new IllegalStateException("读取语料目录失败：" + directory, failure);
        }
    }

    static List<String> corpusDocumentIds() {
        return corpusFiles().stream()
                .map(path -> path.getFileName().toString())
                .map(name -> name.substring(0, name.length() - ".md".length()))
                .toList();
    }

    record IngestedDocument(String documentId, int chunkCount) { }

    /**
     * 自动灌库。每次评测都在干净的容器库里重灌，所以不需要处理版本冲突——
     * 这也意味着评测结果不依赖任何手工前置步骤。
     */
    static List<IngestedDocument> ingestCorpus(PgVectorKnowledgeStore store, String actorUserId) {
        List<IngestedDocument> ingested = new ArrayList<>();
        for (Path file : corpusFiles()) {
            String documentId = file.getFileName().toString().replaceFirst("\\.md$", "");
            String content = read(file);
            PgVectorKnowledgeStore.IndexedDocument indexed = store.index(
                    new PgVectorKnowledgeStore.IndexDocument(documentId, title(content, documentId),
                            SOURCE_URI_PREFIX + documentId + ".md", CORPUS_VERSION, null, content),
                    actorUserId);
            ingested.add(new IngestedDocument(indexed.documentId(), indexed.chunkCount()));
        }
        return List.copyOf(ingested);
    }

    private static String title(String content, String fallback) {
        return content.lines()
                .filter(line -> line.startsWith("# "))
                .map(line -> line.substring(2).trim())
                .findFirst()
                .orElse(fallback);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (Exception failure) {
            throw new IllegalStateException("语料读取失败：" + file, failure);
        }
    }
}
