package com.buyforu.agent.infrastructure.persistence;

import com.buyforu.agent.application.AgentRunStore;
import com.buyforu.agent.domain.ShoppingAgentState;
import com.buyforu.agent.concurrency.CommandExceptions.StaleExecution;
import com.buyforu.agent.concurrency.ExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * ShoppingAgentState 的 PostgreSQL 仓库。
 * 每次保存同时追加业务 checkpoint，并同步人工审批审计记录。
 */
@Repository
public class JdbcAgentRunStore implements AgentRunStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcAgentRunStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional
    public ShoppingAgentState save(ShoppingAgentState state) {
        String payload = json.writeValueAsString(state);
        ExecutionContext execution = ExecutionContext.current();
        if (execution == null) {
            saveWithoutLease(state, payload);
        } else {
            saveFenced(state, payload, execution);
        }

        jdbc.update("""
                INSERT INTO agent_schema.agent_checkpoint (run_id, checkpoint_version, state)
                SELECT ?, COALESCE(MAX(checkpoint_version), 0) + 1, CAST(? AS jsonb)
                FROM agent_schema.agent_checkpoint WHERE run_id = ?
                """, state.runId(), payload, state.runId());
        synchronizeApproval(state);
        return state;
    }

    private void saveWithoutLease(ShoppingAgentState state, String payload) {
        // 单元测试和管理恢复路径不带 Worker 上下文；生产命令执行始终走 saveFenced。
        jdbc.update("""
                INSERT INTO agent_schema.agent_run
                    (run_id, conversation_id, user_id, trace_id, phase, state, plan_version, updated_at,state_version)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?,0)
                ON CONFLICT (run_id) DO UPDATE SET
                    phase = EXCLUDED.phase,
                    state = EXCLUDED.state,
                    plan_version = EXCLUDED.plan_version,
                    state_version = agent_schema.agent_run.state_version + 1,
                    updated_at = EXCLUDED.updated_at
                """, state.runId(), state.conversationId(), state.userId(), state.traceId(),
                state.phase().name(), payload, state.planVersion(), Timestamp.from(state.updatedAt()));
    }

    private void saveFenced(ShoppingAgentState state, String payload, ExecutionContext execution) {
        long expectedVersion = execution.expectedStateVersion();
        int updated = expectedVersion < 0 ? 0 : jdbc.update("""
                UPDATE agent_schema.agent_run SET phase=?,state=CAST(? AS jsonb),plan_version=?,updated_at=?,
                    state_version=state_version+1
                WHERE run_id=? AND state_version=? AND EXISTS (
                    SELECT 1 FROM agent_schema.agent_run_execution x
                    WHERE x.run_id=? AND x.active_command_id=? AND x.execution_epoch=? AND x.lease_until>now())
                """, state.phase().name(), payload, state.planVersion(), Timestamp.from(state.updatedAt()),
                state.runId(), expectedVersion, state.runId(), execution.commandId(), execution.epoch());
        if (updated == 0 && expectedVersion < 0) {
            updated = jdbc.update("""
                    INSERT INTO agent_schema.agent_run
                        (run_id,conversation_id,user_id,trace_id,phase,state,plan_version,updated_at,state_version)
                    SELECT ?,?,?,?,?,CAST(? AS jsonb),?,?,0
                    WHERE EXISTS (SELECT 1 FROM agent_schema.agent_run_execution x
                        WHERE x.run_id=? AND x.active_command_id=? AND x.execution_epoch=? AND x.lease_until>now())
                    ON CONFLICT DO NOTHING
                    """, state.runId(), state.conversationId(), state.userId(), state.traceId(), state.phase().name(),
                    payload, state.planVersion(), Timestamp.from(state.updatedAt()), state.runId(),
                    execution.commandId(), execution.epoch());
        }
        if (updated != 1) throw new StaleExecution(state.runId());
        execution.stateSaved();
    }

    @Override
    public Optional<ShoppingAgentState> find(String runId) {
        return jdbc.query("SELECT state::text FROM agent_schema.agent_run WHERE run_id = ?",
                (result, row) -> readState(result), runId).stream().findFirst();
    }

    @Override
    public List<ShoppingAgentState> findRecentByUser(String userId, int limit) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId is required");
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("run limit must be between 1 and 50");
        return jdbc.query("""
                SELECT state::text FROM agent_schema.agent_run
                WHERE user_id = ? ORDER BY updated_at DESC, run_id DESC LIMIT ?
                """, (result, row) -> readState(result), userId, limit);
    }

    @Override
    @Transactional
    public ShoppingAgentState update(String runId, UnaryOperator<ShoppingAgentState> mutation) {
        ShoppingAgentState current = jdbc.query("""
                        SELECT state::text FROM agent_schema.agent_run
                        WHERE run_id = ? FOR UPDATE
                        """, (result, row) -> readState(result), runId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("run not found: " + runId));
        return save(mutation.apply(current));
    }

    private ShoppingAgentState readState(ResultSet result) throws SQLException {
        return json.readValue(result.getString(1), ShoppingAgentState.class);
    }

    private void synchronizeApproval(ShoppingAgentState state) {
        if (state.pendingApproval() != null) {
            var approval = state.pendingApproval();
            jdbc.update("""
                    INSERT INTO agent_schema.approval_request
                        (approval_id, run_id, snapshot_id, summary_hash, expires_at, user_id)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (approval_id) DO NOTHING
                    """, approval.approvalRequestId(), state.runId(), approval.snapshotId(),
                    approval.expectedSummaryHash(), Timestamp.from(approval.expiresAt()), state.userId());
            jdbc.update("""
                    UPDATE agent_schema.approval_request
                    SET decision = CASE WHEN expires_at <= now() THEN 'EXPIRED' ELSE 'SUPERSEDED' END,
                        decision_reason = CASE WHEN expires_at <= now() THEN 'approval expired'
                                               ELSE 'replaced by a newer confirmable snapshot' END,
                        decided_at = now()
                    WHERE run_id = ? AND decision IS NULL AND approval_id <> ?
                    """, state.runId(), approval.approvalRequestId());
        }
        if (state.phase() == ShoppingAgentState.Phase.COMPLETED) {
            jdbc.update("""
                    UPDATE agent_schema.approval_request
                    SET decision = 'APPROVE', decision_reason = 'user confirmed current snapshot',
                        decided_by = ?, decided_at = ?
                    WHERE run_id = ? AND decision IS NULL AND snapshot_id = ?
                    """, state.userId(), Timestamp.from(state.updatedAt()), state.runId(),
                    state.finalOrder().sourceSnapshotId());
        } else if (state.phase() == ShoppingAgentState.Phase.CANCELLED) {
            jdbc.update("""
                    UPDATE agent_schema.approval_request
                    SET decision = 'REJECT', decision_reason = 'user rejected or cancelled',
                        decided_by = ?, decided_at = ?
                    WHERE run_id = ? AND decision IS NULL
                    """, state.userId(), Timestamp.from(state.updatedAt()), state.runId());
        }
    }
}
