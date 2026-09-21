package com.buyforu.agent.concurrency;

import com.buyforu.agent.application.RunExecutionGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 每个 run 的跨实例短租约。claim 提交后数据库连接立即归还；epoch 是防止旧 Worker 写回的栅栏令牌。
 */
@Repository
public class RunLeaseRepository implements RunExecutionGuard {
    private final JdbcTemplate jdbc;

    public RunLeaseRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public Optional<Lease> claim(AgentCommand command, String owner, Instant leaseUntil) {
        jdbc.update("""
                INSERT INTO agent_schema.agent_run_execution(run_id) VALUES (?) ON CONFLICT DO NOTHING
                """, command.runId());
        var rows = jdbc.query("""
                SELECT execution_epoch,active_command_id,lease_until,cancel_requested
                FROM agent_schema.agent_run_execution WHERE run_id=? FOR UPDATE
                """, (rs, row) -> new LeaseRow(rs.getLong(1), rs.getObject(2, UUID.class),
                rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(), rs.getBoolean(4)), command.runId());
        LeaseRow row = rows.getFirst();
        Instant now = Instant.now();
        if (row.activeCommandId() != null && row.leaseUntil() != null && row.leaseUntil().isAfter(now)) {
            return Optional.empty();
        }
        if (row.cancelRequested() && command.queueClass() != AgentCommand.QueueClass.CONTROL) {
            // CANCEL 已先写入 PostgreSQL。后续普通命令不能因为重新领取租约而把取消标记清掉。
            jdbc.update("""
                    UPDATE agent_schema.agent_command SET status='CANCELLED',error_code='RUN_CANCEL_REQUESTED',
                        completed_at=now() WHERE command_id=? AND status IN ('QUEUED','RETRY_WAIT')
                    """, command.commandId());
            return Optional.empty();
        }
        if (row.activeCommandId() != null) {
            // 先恢复上一任已过期 Worker，再让新的命令竞争。这样不会覆盖 active_command_id，
            // 也不会留下一个永远停在 RUNNING 的孤儿命令。
            int recovered = recoverCommand(row.activeCommandId());
            jdbc.update("""
                    UPDATE agent_schema.agent_run_execution SET active_command_id=NULL,lease_owner=NULL,
                        lease_until=NULL,updated_at=now() WHERE run_id=? AND active_command_id=?
                    """, command.runId(), row.activeCommandId());
            // 若正在领取的就是刚恢复的同一命令，可在本事务内直接换新 epoch；
            // 若是另一个命令，则先让旧命令重新进入公平队列，避免越过 run 内顺序。
            if (recovered == 1 && !row.activeCommandId().equals(command.commandId())) return Optional.empty();
        }
        long epoch = row.epoch() + 1;
        int updated = jdbc.update("""
                UPDATE agent_schema.agent_run_execution SET execution_epoch=?,active_command_id=?,lease_owner=?,
                    lease_until=?,cancel_requested=false,updated_at=now() WHERE run_id=?
                """, epoch, command.commandId(), owner, Timestamp.from(leaseUntil), command.runId());
        int commandUpdated = jdbc.update("""
                UPDATE agent_schema.agent_command SET status='RUNNING',attempts=attempts+1,started_at=COALESCE(started_at,now()),
                    execution_epoch=? WHERE command_id=?
                    AND (status='QUEUED' OR (status='RETRY_WAIT' AND available_at<=now()))
                """, epoch, command.commandId());
        if (updated != 1 || commandUpdated != 1) throw new ClaimConflict();
        Long stateVersion = jdbc.queryForObject("""
                SELECT COALESCE((SELECT state_version FROM agent_schema.agent_run WHERE run_id=?),-1)
                """, Long.class, command.runId());
        return Optional.of(new Lease(command.commandId(), command.runId(), owner, epoch, leaseUntil,
                stateVersion == null ? -1 : stateVersion));
    }

    /** 崩溃实例不会续租；到期后把命令恢复为 RETRY_WAIT 或按次数/期限终止。 */
    @Transactional
    public int recoverExpired() {
        int recovered = jdbc.update("""
                UPDATE agent_schema.agent_command c SET
                    status=CASE WHEN c.attempts>=3 OR c.deadline_at<=now() THEN 'EXPIRED' ELSE 'RETRY_WAIT' END,
                    available_at=CASE WHEN c.attempts>=3 OR c.deadline_at<=now() THEN c.available_at ELSE now() END,
                    error_code=CASE WHEN c.attempts>=3 OR c.deadline_at<=now() THEN 'COMMAND_RECOVERY_EXHAUSTED'
                                    ELSE 'WORKER_LEASE_EXPIRED' END,
                    completed_at=CASE WHEN c.attempts>=3 OR c.deadline_at<=now() THEN now() ELSE NULL END
                FROM agent_schema.agent_run_execution x
                WHERE c.command_id=x.active_command_id AND c.status='RUNNING' AND x.lease_until<=now()
                """);
        jdbc.update("""
                UPDATE agent_schema.agent_run_execution SET active_command_id=NULL,lease_owner=NULL,lease_until=NULL,
                    updated_at=now() WHERE active_command_id IS NOT NULL AND lease_until<=now()
                """);
        // 防御“租约行已被清掉/覆盖，但命令仍是 RUNNING”的历史数据与异常窗口。
        recovered += jdbc.update("""
                UPDATE agent_schema.agent_command c SET
                    status=CASE WHEN c.attempts>=3 OR c.deadline_at<=now() THEN 'EXPIRED' ELSE 'RETRY_WAIT' END,
                    available_at=CASE WHEN c.attempts>=3 OR c.deadline_at<=now() THEN c.available_at ELSE now() END,
                    error_code=CASE WHEN c.attempts>=3 OR c.deadline_at<=now() THEN 'COMMAND_RECOVERY_EXHAUSTED'
                                    ELSE 'ORPHAN_RUNNING_COMMAND' END,
                    completed_at=CASE WHEN c.attempts>=3 OR c.deadline_at<=now() THEN now() ELSE NULL END
                WHERE c.status='RUNNING'
                  AND NOT EXISTS (
                      SELECT 1 FROM agent_schema.agent_run_execution x
                      WHERE x.active_command_id=c.command_id AND x.lease_until>now()
                  )
                """);
        return recovered;
    }

    private int recoverCommand(UUID commandId) {
        return jdbc.update("""
                UPDATE agent_schema.agent_command SET
                    status=CASE WHEN attempts>=3 OR deadline_at<=now() THEN 'EXPIRED' ELSE 'RETRY_WAIT' END,
                    available_at=CASE WHEN attempts>=3 OR deadline_at<=now() THEN available_at ELSE now() END,
                    error_code=CASE WHEN attempts>=3 OR deadline_at<=now() THEN 'COMMAND_RECOVERY_EXHAUSTED'
                                    ELSE 'WORKER_LEASE_EXPIRED' END,
                    completed_at=CASE WHEN attempts>=3 OR deadline_at<=now() THEN now() ELSE NULL END
                WHERE command_id=? AND status='RUNNING'
                """, commandId);
    }

    public boolean heartbeat(Lease lease, Instant leaseUntil) {
        return jdbc.update("""
                UPDATE agent_schema.agent_run_execution SET lease_until=?,updated_at=now()
                WHERE run_id=? AND active_command_id=? AND execution_epoch=? AND lease_owner=?
                """, Timestamp.from(leaseUntil), lease.runId(), lease.commandId(), lease.epoch(), lease.owner()) == 1;
    }

    @Override
    public boolean hasConflictingLiveLease(String runId) {
        ExecutionContext current = ExecutionContext.current();
        UUID currentCommandId = current != null && runId.equals(current.runId()) ? current.commandId() : null;
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM agent_schema.agent_run_execution
                WHERE run_id=? AND active_command_id IS NOT NULL AND lease_until>now()
                  AND (?::uuid IS NULL OR active_command_id<>?::uuid)
                """, Integer.class, runId, currentCommandId, currentCommandId);
        return count != null && count > 0;
    }

    public boolean isCurrent(String runId, UUID commandId, long epoch) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM agent_schema.agent_run_execution
                WHERE run_id=? AND active_command_id=? AND execution_epoch=? AND lease_until>now()
                """, Integer.class, runId, commandId, epoch);
        return count != null && count == 1;
    }

    public boolean cancellationRequested(Lease lease) {
        // 不能用 queryForObject：租约行被 recoverExpired 清空，或被更高 epoch 的实例接管后，
        // 三元组匹配不到任何行，queryForObject 会抛 EmptyResultDataAccessException。
        // 本方法由 heartbeat 在遍历 activeLeases 的循环里调用，抛异常会中断整轮续租——
        // 一个陈旧租约就会拖垮同实例上所有还在跑的命令。
        // 查不到时返回 false，交由后续 heartbeat(...) 的更新命中 0 行判定为租约丢失并摘除。
        var rows = jdbc.query("""
                SELECT cancel_requested FROM agent_schema.agent_run_execution
                WHERE run_id=? AND active_command_id=? AND execution_epoch=?
                """, (rs, row) -> rs.getBoolean(1), lease.runId(), lease.commandId(), lease.epoch());
        return !rows.isEmpty() && Boolean.TRUE.equals(rows.getFirst());
    }

    public void requestCancellation(String runId) {
        jdbc.update("""
                INSERT INTO agent_schema.agent_run_execution(run_id,cancel_requested) VALUES (?,true)
                ON CONFLICT(run_id) DO UPDATE SET cancel_requested=true,updated_at=now()
                """, runId);
    }

    public void release(Lease lease) {
        jdbc.update("""
                UPDATE agent_schema.agent_run_execution SET active_command_id=NULL,lease_owner=NULL,lease_until=NULL,
                    cancel_requested=false,updated_at=now()
                WHERE run_id=? AND active_command_id=? AND execution_epoch=? AND lease_owner=?
                """, lease.runId(), lease.commandId(), lease.epoch(), lease.owner());
    }

    private record LeaseRow(long epoch, UUID activeCommandId, Instant leaseUntil, boolean cancelRequested) { }

    public record Lease(UUID commandId, String runId, String owner, long epoch, Instant leaseUntil,
                        long stateVersion) { }

    public static final class ClaimConflict extends RuntimeException { }
}
