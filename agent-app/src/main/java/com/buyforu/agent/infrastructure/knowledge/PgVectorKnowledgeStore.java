package com.buyforu.agent.infrastructure.knowledge;

import com.buyforu.agent.application.KnowledgeRetriever;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import com.buyforu.agent.concurrency.DependencyExecutor;
import java.time.Duration;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.HexFormat;

/**
 * pgvector 知识库：负责切块、生成 Embedding、版本化替换和相似度召回。
 * 文档更新采用 expectedVersion 乐观并发控制，避免管理员覆盖彼此修改。
 */
@Repository
public class PgVectorKnowledgeStore implements KnowledgeRetriever {
    private static final int MAX_CHUNK_CHARACTERS = 1200;

    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper json;
    private final Clock clock;
    private final String embeddingModelName;
    private final DependencyExecutor dependencies;
    private final TransactionTemplate transactions;

    @Autowired
    public PgVectorKnowledgeStore(JdbcTemplate jdbc, EmbeddingModel embeddingModel, ObjectMapper json,
                                  @org.springframework.beans.factory.annotation.Value("${spring.ai.ollama.embedding.model}")
                                  String embeddingModelName, DependencyExecutor dependencies,
                                  TransactionTemplate transactions) {
        this(jdbc, embeddingModel, json, embeddingModelName, Clock.systemUTC(), dependencies, transactions);
    }

    PgVectorKnowledgeStore(JdbcTemplate jdbc, EmbeddingModel embeddingModel, ObjectMapper json,
                           String embeddingModelName, Clock clock, DependencyExecutor dependencies,
                           TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
        this.json = json;
        this.clock = clock;
        this.embeddingModelName = embeddingModelName;
        this.dependencies = dependencies;
        this.transactions = transactions;
    }

    public IndexedDocument index(IndexDocument command, String actorUserId) {
        command.validate();
        if (actorUserId == null || actorUserId.isBlank()) {
            throw new SecurityException("knowledge audit actor is required");
        }
        // 同一语料库禁止混用 Embedding 模型，否则不同向量空间的相似度没有意义。
        assertCompatibleCorpus();
        List<String> chunks = chunk(command.content());
        // Embedding 在事务外完成，避免 Ollama 网络等待占用 PostgreSQL 连接。
        List<float[]> embeddings = dependencies.call(DependencyExecutor.Dependency.EMBEDDING,
                Duration.ofSeconds(10), 1, () -> embeddingModel.embed(chunks));
        if (embeddings.size() != chunks.size()) {
            throw new IllegalStateException("embedding model returned an unexpected result count");
        }
        embeddings.forEach(this::validateEmbedding);

        return transactions.execute(status -> persist(command, actorUserId, chunks, embeddings));
    }

    private IndexedDocument persist(IndexDocument command, String actorUserId, List<String> chunks,
                                    List<float[]> embeddings) {
        String documentId = command.documentId() == null || command.documentId().isBlank()
                ? UUID.randomUUID().toString() : command.documentId();
        List<String> currentVersions = jdbc.query("""
                SELECT version FROM agent_schema.knowledge_document
                WHERE document_id = ? FOR UPDATE
                """, (result, row) -> result.getString(1), documentId);
        if (!currentVersions.isEmpty()) {
            String current = currentVersions.getFirst();
            if (!Objects.equals(command.expectedVersion(), current)) {
                throw new IllegalStateException("document version conflict; current version is " + current);
            }
            if (command.version().equals(current)) {
                throw new IllegalArgumentException("new knowledge version must differ from the current version");
            }
        } else if (command.expectedVersion() != null && !command.expectedVersion().isBlank()) {
            throw new IllegalStateException("document does not exist, so expectedVersion must be empty");
        }
        Instant indexedAt = clock.instant();
        jdbc.update("""
                INSERT INTO agent_schema.knowledge_document
                    (document_id, title, source_uri, version, updated_at, embedding_model, embedding_dimensions)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (document_id) DO UPDATE SET title = EXCLUDED.title,
                    source_uri = EXCLUDED.source_uri, version = EXCLUDED.version,
                    updated_at = EXCLUDED.updated_at, embedding_model = EXCLUDED.embedding_model,
                    embedding_dimensions = EXCLUDED.embedding_dimensions
                """, documentId, command.title(), command.sourceUri(), command.version(), Timestamp.from(indexedAt),
                embeddingModelName, embeddings.getFirst().length);
        jdbc.update("DELETE FROM agent_schema.knowledge_chunk WHERE document_id = ?", documentId);

        for (int index = 0; index < chunks.size(); index++) {
            String chunkId = documentId + ":" + index;
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("chunkIndex", index);
            metadata.put("sourceUri", command.sourceUri());
            metadata.put("version", command.version());
            jdbc.update("""
                    INSERT INTO agent_schema.knowledge_chunk(chunk_id, document_id, content, embedding, metadata)
                    VALUES (?, ?, ?, CAST(? AS vector), CAST(? AS jsonb))
                    """, chunkId, documentId, chunks.get(index), vectorLiteral(embeddings.get(index)),
                    json.writeValueAsString(metadata));
        }
        jdbc.update("""
                INSERT INTO agent_schema.knowledge_audit
                    (audit_id, document_id, version, actor_user_id, source_uri, content_digest)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), documentId, command.version(), actorUserId,
                command.sourceUri(), digest(command.content()));
        return new IndexedDocument(documentId, command.version(), chunks.size(), indexedAt);
    }

    @Override
    public List<KnowledgeHit> retrieve(String query, int limit, double minimumScore) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("knowledge query is required");
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("knowledge limit must be between 1 and 20");
        if (minimumScore < -1 || minimumScore > 1) {
            throw new IllegalArgumentException("minimumScore must be between -1 and 1");
        }
        assertCompatibleCorpus();
        float[] queryEmbedding = dependencies.call(DependencyExecutor.Dependency.EMBEDDING,
                Duration.ofSeconds(10), 1, () -> embeddingModel.embed(query));
        validateEmbedding(queryEmbedding);
        String vector = vectorLiteral(queryEmbedding);
        return jdbc.query("""
                SELECT d.document_id, d.title, d.source_uri, d.version,
                       c.chunk_id, c.content, 1 - (c.embedding <=> CAST(? AS vector)) AS score
                FROM agent_schema.knowledge_chunk c
                JOIN agent_schema.knowledge_document d ON d.document_id = c.document_id
                WHERE c.embedding IS NOT NULL
                  AND d.embedding_model = ?
                  AND 1 - (c.embedding <=> CAST(? AS vector)) >= ?
                ORDER BY c.embedding <=> CAST(? AS vector), d.updated_at DESC
                LIMIT ?
                """, (rs, row) -> new KnowledgeHit(
                        rs.getString("document_id"), rs.getString("title"), rs.getString("source_uri"),
                        rs.getString("version"), rs.getString("chunk_id"), rs.getString("content"),
                        rs.getDouble("score")), vector, embeddingModelName, vector, minimumScore, vector, limit);
    }

    private void validateEmbedding(float[] embedding) {
        if (embedding == null || embedding.length < 64) {
            throw new IllegalStateException("embedding model returned an invalid vector");
        }
    }

    private void assertCompatibleCorpus() {
        List<String> incompatible = jdbc.query("""
                SELECT DISTINCT COALESCE(embedding_model, '<legacy>')
                FROM agent_schema.knowledge_document
                WHERE embedding_model IS DISTINCT FROM ?
                """, (result, row) -> result.getString(1), embeddingModelName);
        if (!incompatible.isEmpty()) {
            throw new IllegalStateException("knowledge corpus uses incompatible embedding model(s) "
                    + incompatible + "; rebuild the corpus before using " + embeddingModelName);
        }
    }

    static List<String> chunk(String content) {
        String normalized = content.replace("\r\n", "\n").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("knowledge content is required");
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : normalized.split("\\n\\s*\\n")) {
            String value = paragraph.trim();
            if (value.isEmpty()) continue;
            while (value.length() > MAX_CHUNK_CHARACTERS) {
                flush(current, result);
                result.add(value.substring(0, MAX_CHUNK_CHARACTERS));
                value = value.substring(MAX_CHUNK_CHARACTERS).trim();
            }
            if (current.length() > 0 && current.length() + 2 + value.length() > MAX_CHUNK_CHARACTERS) {
                flush(current, result);
            }
            if (current.length() > 0) current.append("\n\n");
            current.append(value);
        }
        flush(current, result);
        return List.copyOf(result);
    }

    private static void flush(StringBuilder current, List<String> result) {
        if (current.length() > 0) {
            result.add(current.toString());
            current.setLength(0);
        }
    }

    private static String vectorLiteral(float[] vector) {
        StringBuilder result = new StringBuilder(vector.length * 10).append('[');
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) result.append(',');
            result.append(Float.toString(vector[index]));
        }
        return result.append(']').toString();
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record IndexDocument(String documentId, String title, String sourceUri, String version,
                                String expectedVersion, String content) {
        void validate() {
            if (title == null || title.isBlank()) throw new IllegalArgumentException("title is required");
            if (sourceUri == null || sourceUri.isBlank()) throw new IllegalArgumentException("sourceUri is required");
            try {
                URI.create(sourceUri);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("sourceUri must be a valid URI", invalid);
            }
            if (version == null || version.isBlank()) throw new IllegalArgumentException("version is required");
            Objects.requireNonNull(content, "content is required");
        }
    }

    public record IndexedDocument(String documentId, String version, int chunkCount, Instant indexedAt) {
    }
}
