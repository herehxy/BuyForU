package com.buyforu.agent.concurrency;

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
public class RunLeaseRepository {
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
        long epoch = row.epoch() + 1;
        int updated = jdbc.update("""
                UPDATE agent_schema.agent_run_execution SET execution_epoch=?,active_command_id=?,lease_owner=?,
                    lease_until=?,cancel_requested=false,updated_at=now() WHERE run_id=?
                """, epoch, command.commandId(), owner, Timestamp.from(leaseUntil), command.runId());
        int commandUpdated = jdbc.update("""
                UPDATE agent_schema.agent_command SET status='RUNNING',attempts=attempts+1,started_at=COALESCE(started_at,now()),
                    execution_epoch=? WHERE command_id=? AND status IN ('QUEUED','RETRY_WAIT') AND available_at<=now()
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
                    available_at=CASE WHEN c.attempts>=3 OR c.deadline_at<=now() THEN c.available_at
                                      ELSE now()+interval '1 second' END,
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
        return recovered;
    }

    public boolean heartbeat(Lease lease, Instant leaseUntil) {
        return jdbc.update("""
                UPDATE agent_schema.agent_run_execution SET lease_until=?,updated_at=now()
                WHERE run_id=? AND active_command_id=? AND execution_epoch=? AND lease_owner=?
                """, Timestamp.from(leaseUntil), lease.runId(), lease.commandId(), lease.epoch(), lease.owner()) == 1;
    }

    public boolean isCurrent(String runId, UUID commandId, long epoch) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM agent_schema.agent_run_execution
                WHERE run_id=? AND active_command_id=? AND execution_epoch=? AND lease_until>now()
                """, Integer.class, runId, commandId, epoch);
        return count != null && count == 1;
    }

    public boolean cancellationRequested(Lease lease) {
        Boolean requested = jdbc.queryForObject("""
                SELECT cancel_requested FROM agent_schema.agent_run_execution
                WHERE run_id=? AND active_command_id=? AND execution_epoch=?
                """, Boolean.class, lease.runId(), lease.commandId(), lease.epoch());
        return Boolean.TRUE.equals(requested);
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
