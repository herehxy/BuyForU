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

    public Optional<AgentCommand> findByIdempotency(String userId, String runId, String key) {
        return jdbc.query("""
                SELECT * FROM agent_schema.agent_command
                WHERE user_id=? AND run_id=? AND idempotency_key=?
                """, (rs, row) -> map(rs), userId, runId, key).stream().findFirst();
    }

    public Optional<AgentCommand> findOwned(UUID commandId, String userId) {
        return jdbc.query("SELECT * FROM agent_schema.agent_command WHERE command_id=? AND user_id=?",
                (rs, row) -> map(rs), commandId, userId).stream().findFirst();
    }

    public Optional<AgentCommand> find(UUID commandId) {
        return jdbc.query("SELECT * FROM agent_schema.agent_command WHERE command_id=?",
                (rs, row) -> map(rs), commandId).stream().findFirst();
    }

    /**
     * 主人只认业务 run 行，或尚未落库时的 START 命令。
     * 不能用“该用户是否写过任意命令”，否则攻击者先插一条 CANCEL 就能订阅 SSE。
     */
    public Optional<String> runOwner(String runId) {
        return jdbc.query("""
                SELECT user_id FROM agent_schema.agent_run WHERE run_id=?
                UNION ALL
                SELECT user_id FROM agent_schema.agent_command
                WHERE run_id=? AND command_type='START'
                LIMIT 1
                """, (rs, row) -> rs.getString(1), runId, runId).stream().findFirst();
    }

    public boolean ownsRun(String runId, String userId) {
        return runOwner(runId).filter(userId::equals).isPresent();
    }

    public boolean runStateExists(String runId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM agent_schema.agent_run WHERE run_id=?", Integer.class, runId);
        return count != null && count > 0;
    }

    public int pendingControlCount(String userId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM agent_schema.agent_command
                WHERE user_id=? AND queue_class='CONTROL' AND status IN ('QUEUED','RUNNING','RETRY_WAIT')
                """, Integer.class, userId);
        return count == null ? 0 : count;
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

    /** 重建调度索引时按创建时间捞待执行命令；调用方一次扫完全部有界队列即可。 */
    public List<AgentCommand> queuedWithoutIndex(AgentCommand.QueueClass lane, int limit) {
        return jdbc.query("""
                SELECT * FROM agent_schema.agent_command
                WHERE queue_class=? AND status IN ('QUEUED','RETRY_WAIT') AND available_at<=now()
                ORDER BY created_at LIMIT ?
                """, (rs, row) -> map(rs), lane.name(), limit);
    }

    /** 跳过租约还在别人手里的 run，避免队头一条 CANCEL 堵住所有人的取消。 */
    public List<AgentCommand> controlReady(int limit) {
        return jdbc.query("""
                SELECT c.*
                FROM agent_schema.agent_command c
                WHERE c.queue_class='CONTROL'
                  AND c.status='QUEUED'
                  AND c.available_at<=now()
                  AND NOT EXISTS (
                      SELECT 1 FROM agent_schema.agent_run_execution x
                      WHERE x.run_id=c.run_id
                        AND x.active_command_id IS NOT NULL
                        AND x.lease_until>now()
                        AND x.active_command_id<>c.command_id
                  )
                ORDER BY c.created_at
                FOR UPDATE SKIP LOCKED
                LIMIT ?
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

    /** 取消先终止尚未领取的业务命令；正在执行的命令由 cancel_requested + Future interrupt 处理。 */
    public int cancelPendingForRun(String runId) {
        return jdbc.update("""
                UPDATE agent_schema.agent_command SET status='CANCELLED',error_code='RUN_CANCEL_REQUESTED',
                    completed_at=now()
                WHERE run_id=? AND queue_class<>'CONTROL' AND status IN ('QUEUED','RETRY_WAIT')
                """, runId);
    }

    public void markCancelled(UUID commandId, String code) {
        jdbc.update("""
                UPDATE agent_schema.agent_command SET status='CANCELLED',error_code=?,completed_at=now()
                WHERE command_id=? AND status IN ('QUEUED','RUNNING','RETRY_WAIT')
                """, code, commandId);
    }

    public List<AgentCommand> recentlyRecovered(int withinSeconds) {
        return jdbc.query("""
                SELECT * FROM agent_schema.agent_command
                WHERE error_code IN ('WORKER_LEASE_EXPIRED','ORPHAN_RUNNING_COMMAND','COMMAND_RECOVERY_EXHAUSTED')
                  AND (
                    (status='RETRY_WAIT' AND available_at>=now() - (? * interval '1 second'))
                    OR (status IN ('EXPIRED','FAILED') AND completed_at>=now() - (? * interval '1 second'))
                  )
                """, (rs, row) -> map(rs), withinSeconds, withinSeconds);
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
