package com.buyforu.agent.concurrency;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** SSE 的持久化事件日志；断线续传读取此表，而不是依赖易失的 Redis Pub/Sub。 */
@Repository
public class RunEventRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RunEventNotifier notifier;

    public RunEventRepository(JdbcTemplate jdbc, ObjectMapper json, RunEventNotifier notifier) {
        this.jdbc = jdbc; this.json = json; this.notifier = notifier;
    }

    public RunEvent append(String runId, UUID commandId, String type, Object payload) {
        RunEvent event = jdbc.queryForObject("""
                INSERT INTO agent_schema.agent_run_event(run_id,command_id,event_type,payload)
                VALUES (?,?,?,CAST(? AS jsonb))
                RETURNING event_id,created_at
                """, (rs, row) -> new RunEvent(rs.getLong(1), runId, commandId, type,
                json.convertValue(payload, Map.class), rs.getTimestamp(2).toInstant()), runId, commandId, type,
                json.writeValueAsString(payload));
        notifier.publish(runId);
        return event;
    }

    public List<RunEvent> after(String runId, long lastEventId, int limit) {
        return jdbc.query("""
                SELECT event_id,command_id,event_type,payload::text,created_at
                FROM agent_schema.agent_run_event WHERE run_id=? AND event_id>? ORDER BY event_id LIMIT ?
                """, (rs, row) -> new RunEvent(rs.getLong(1), runId, rs.getObject(2, UUID.class), rs.getString(3),
                json.readValue(rs.getString(4), Map.class), rs.getTimestamp(5).toInstant()), runId, lastEventId, limit);
    }

    public int deleteOlderThan(Instant cutoff, int limit) {
        return jdbc.update("""
                DELETE FROM agent_schema.agent_run_event WHERE event_id IN
                    (SELECT event_id FROM agent_schema.agent_run_event WHERE created_at<? ORDER BY event_id LIMIT ?)
                """, Timestamp.from(cutoff), limit);
    }

    public record RunEvent(long eventId, String runId, UUID commandId, String eventType,
                           Map<String, Object> payload, Instant createdAt) { }
}
