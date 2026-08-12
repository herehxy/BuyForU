package com.buyforu.agent.concurrency;

import com.buyforu.agent.concurrency.AgentCommand.CommandStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 持久化命令仓库；所有状态迁移使用条件更新，避免无条件覆盖并发结果。 */
@Repository
public class CommandRepository {
    private final JdbcTemplate jdbc;

    public CommandRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<AgentCommand> findByIdempotency(String userId, String key) {
        return jdbc.query("SELECT * FROM agent_schema.agent_command WHERE user_id=? AND idempotency_key=?",
                (rs, row) -> map(rs), userId, key).stream().findFirst();
    }

    public Optional<AgentCommand> findOwned(UUID commandId, String userId) {
        return jdbc.query("SELECT * FROM agent_schema.agent_command WHERE command_id=? AND user_id=?",
                (rs, row) -> map(rs), commandId, userId).stream().findFirst();
    }

    public Optional<AgentCommand> find(UUID commandId) {
        return jdbc.query("SELECT * FROM agent_schema.agent_command WHERE command_id=?",
                (rs, row) -> map(rs), commandId).stream().findFirst();
    }

    public boolean ownsRun(String runId, String userId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM agent_schema.agent_command WHERE run_id=? AND user_id=?
                """, Integer.class, runId, userId);
        return count != null && count > 0;
    }

    @Transactional
    public AgentCommand insert(AgentCommand command) {
        jdbc.update("""
                INSERT INTO agent_schema.agent_command
                    (command_id,run_id,user_id,command_type,queue_class,idempotency_key,request_hash,payload,
                     status,attempts,available_at,deadline_at,created_at)
                VALUES (?,?,?,?,?,?,?,CAST(? AS jsonb),?,?,?, ?,?)
                """, command.commandId(), command.runId(), command.userId(), command.commandType().name(),
                command.queueClass().name(), command.idempotencyKey(), command.requestHash(), command.payload(),
                command.status().name(), command.attempts(), Timestamp.from(command.availableAt()),
                Timestamp.from(command.deadlineAt()), Timestamp.from(command.createdAt()));
        return command;
    }

    public List<AgentCommand> queuedWithoutIndex(AgentCommand.QueueClass lane, int limit) {
        return jdbc.query("""
                SELECT * FROM agent_schema.agent_command
                WHERE queue_class=? AND status IN ('QUEUED','RETRY_WAIT') AND available_at<=now()
                ORDER BY created_at LIMIT ?
                """, (rs, row) -> map(rs), lane.name(), limit);
    }

    public List<AgentCommand> controlReady(int limit) {
        return jdbc.query("""
                SELECT * FROM agent_schema.agent_command
                WHERE queue_class='CONTROL' AND status='QUEUED' AND available_at<=now()
                ORDER BY created_at LIMIT ?
                """, (rs, row) -> map(rs), limit);
    }

    public void markSucceeded(UUID commandId, CommandStatus status, Long stateVersion) {
        jdbc.update("""
                UPDATE agent_schema.agent_command SET status=?,result_state_version=?,completed_at=now(),
                    error_code=NULL,error_detail=NULL WHERE command_id=? AND status='RUNNING'
                """, status.name(), stateVersion, commandId);
    }

    public void markFailed(UUID commandId, String code, String detail) {
        jdbc.update("""
                UPDATE agent_schema.agent_command SET status='FAILED',error_code=?,error_detail=?,completed_at=now()
                WHERE command_id=? AND status='RUNNING'
                """, code, truncate(detail), commandId);
    }

    public void markExpired(UUID commandId) {
        jdbc.update("""
                UPDATE agent_schema.agent_command SET status='EXPIRED',error_code='COMMAND_DEADLINE_EXCEEDED',
                    completed_at=now() WHERE command_id=? AND status IN ('QUEUED','RETRY_WAIT')
                """, commandId);
    }

    public void markAdmissionRejected(UUID commandId, String code) {
        jdbc.update("""
                UPDATE agent_schema.agent_command SET status='FAILED',error_code=?,completed_at=now()
                WHERE command_id=? AND status='QUEUED'
                """, code, commandId);
    }

    public void retryLater(UUID commandId, Instant availableAt, String code, String detail) {
        jdbc.update("""
                UPDATE agent_schema.agent_command SET status='RETRY_WAIT',available_at=?,error_code=?,error_detail=?
                WHERE command_id=? AND status='RUNNING'
                """, Timestamp.from(availableAt), code, truncate(detail), commandId);
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    private static AgentCommand map(ResultSet rs) throws SQLException {
        return new AgentCommand(rs.getObject("command_id", UUID.class), rs.getString("run_id"),
                rs.getString("user_id"), AgentCommand.CommandType.valueOf(rs.getString("command_type")),
                AgentCommand.QueueClass.valueOf(rs.getString("queue_class")), rs.getString("idempotency_key"),
                rs.getString("request_hash"), rs.getString("payload"), CommandStatus.valueOf(rs.getString("status")),
                rs.getInt("attempts"), instant(rs, "available_at"), instant(rs, "deadline_at"),
                nullableLong(rs, "execution_epoch"), nullableLong(rs, "result_state_version"),
                rs.getString("error_code"), rs.getString("error_detail"), instant(rs, "created_at"),
                instant(rs, "started_at"), instant(rs, "completed_at"));
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        Timestamp value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }
}
