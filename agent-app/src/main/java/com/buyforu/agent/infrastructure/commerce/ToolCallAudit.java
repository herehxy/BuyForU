package com.buyforu.agent.infrastructure.commerce;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * MCP 调用审计仓库。只保存请求和响应摘要、状态及关联标识，不保存完整敏感载荷。
 */
@Repository
public class ToolCallAudit {
    private final JdbcTemplate jdbc;

    public ToolCallAudit(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    String started(String toolName, String runId, String traceId, String effectId, String requestJson) {
        // 先记录 STARTED，进程崩溃后仍能识别未得到响应的外部调用。
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO agent_schema.tool_call
                    (tool_call_id, run_id, trace_id, tool_name, effect_id, status, request_digest, started_at)
                VALUES (?, ?, ?, ?, ?, 'STARTED', ?, ?)
                """, id, runId, traceId, toolName, effectId, digest(requestJson), Timestamp.from(Instant.now()));
        return id;
    }

    void completed(String id, String responseJson) {
        jdbc.update("""
                UPDATE agent_schema.tool_call SET status = 'COMPLETED', response_digest = ?, completed_at = ?
                WHERE tool_call_id = ?
                """, digest(responseJson), Timestamp.from(Instant.now()), id);
    }

    void failed(String id, RuntimeException failure) {
        jdbc.update("""
                UPDATE agent_schema.tool_call SET status = 'FAILED', response_digest = ?, completed_at = ?
                WHERE tool_call_id = ?
                """, digest(failure.getClass().getName() + ":" + failure.getMessage()),
                Timestamp.from(Instant.now()), id);
    }

    /**
     * 清理历史审计记录。原先这张表没有任何保留策略，会永久增长；
     * 现在由 {@code AuditRetentionJob} 按 {@code buyforu.retention.tool-call} 的窗口调用。
     */
    public int deleteOlderThan(Instant cutoff, int limit) {
        return jdbc.update("""
                DELETE FROM agent_schema.tool_call WHERE tool_call_id IN
                    (SELECT tool_call_id FROM agent_schema.tool_call WHERE started_at<? ORDER BY started_at LIMIT ?)
                """, Timestamp.from(cutoff), limit);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
