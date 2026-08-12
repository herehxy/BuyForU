package com.buyforu.agent.infrastructure.persistence;

import com.buyforu.agent.concurrency.CommandExceptions.StaleExecution;
import com.buyforu.agent.concurrency.ExecutionContext;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.AbstractCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.LinkedList;
import java.util.Map;

/**
 * LangGraph4j checkpoint 的 PostgreSQL 适配器。
 * 它保存图节点执行位置；业务结果另由 JdbcAgentRunStore 保存，二者承担不同恢复职责。
 */
@Component
public final class PostgresCheckpointSaver extends AbstractCheckpointSaver {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public PostgresCheckpointSaver(JdbcTemplate jdbc, ObjectMapper json, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.json = json;
        this.transactions = transactions;
    }

    @Override
    protected LinkedList<Checkpoint> loadCheckpoints(RunnableConfig config) {
        String threadId = threadId(config);
        return new LinkedList<>(jdbc.query("""
                SELECT checkpoint_id, node_id, next_node_id, state::text
                FROM agent_schema.langgraph_checkpoint
                WHERE thread_id = ? ORDER BY sequence_no DESC
                """, (result, row) -> Checkpoint.builder()
                .id(result.getString("checkpoint_id"))
                .nodeId(result.getString("node_id"))
                .nextNodeId(result.getString("next_node_id"))
                .state(json.readValue(result.getString("state"),
                        new TypeReference<Map<String, Object>>() { }))
                .build(), threadId));
    }

    @Override
    protected void insertedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints,
                                      Checkpoint checkpoint) {
        upsert(config, checkpoint);
    }

    @Override
    protected void updatedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints,
                                     Checkpoint checkpoint) {
        upsert(config, checkpoint);
    }

    @Override
    protected BaseCheckpointSaver.Tag releaseCheckpoints(RunnableConfig config,
                                                         LinkedList<Checkpoint> checkpoints) {
        return new BaseCheckpointSaver.Tag(threadId(config), ListCopy.copy(checkpoints));
    }

    private void upsert(RunnableConfig config, Checkpoint checkpoint) {
        transactions.executeWithoutResult(status -> {
            ExecutionContext execution = ExecutionContext.current();
            int changed;
            if (execution == null) {
                changed = jdbc.update("""
                INSERT INTO agent_schema.langgraph_checkpoint
                    (thread_id, checkpoint_id, node_id, next_node_id, state, sequence_no)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb),
                    COALESCE((SELECT MAX(sequence_no) + 1 FROM agent_schema.langgraph_checkpoint WHERE thread_id = ?), 1))
                ON CONFLICT (thread_id, checkpoint_id) DO UPDATE SET
                    node_id = EXCLUDED.node_id,
                    next_node_id = EXCLUDED.next_node_id,
                    state = EXCLUDED.state
                """, threadId(config), checkpoint.getId(), checkpoint.getNodeId(), checkpoint.getNextNodeId(),
                        json.writeValueAsString(checkpoint.getState()), threadId(config));
            } else {
                changed = jdbc.update("""
                    INSERT INTO agent_schema.langgraph_checkpoint
                        (thread_id,checkpoint_id,node_id,next_node_id,state,sequence_no,execution_epoch)
                    SELECT ?,?,?,?,CAST(? AS jsonb),COALESCE((SELECT MAX(sequence_no)+1 FROM agent_schema.langgraph_checkpoint
                        WHERE thread_id=?),1),?
                    WHERE EXISTS (SELECT 1 FROM agent_schema.agent_run_execution x
                        WHERE x.run_id=? AND x.active_command_id=? AND x.execution_epoch=? AND x.lease_until>now())
                    ON CONFLICT(thread_id,checkpoint_id) DO UPDATE SET node_id=EXCLUDED.node_id,
                        next_node_id=EXCLUDED.next_node_id,state=EXCLUDED.state,execution_epoch=EXCLUDED.execution_epoch
                    """, threadId(config), checkpoint.getId(), checkpoint.getNodeId(), checkpoint.getNextNodeId(),
                        json.writeValueAsString(checkpoint.getState()), threadId(config), execution.epoch(),
                        execution.runId(), execution.commandId(), execution.epoch());
            }
            if (changed != 1) throw new StaleExecution(threadId(config));
        });
    }

    private static final class ListCopy {
        static Collection<Checkpoint> copy(Collection<Checkpoint> values) {
            return java.util.List.copyOf(values);
        }
    }
}
