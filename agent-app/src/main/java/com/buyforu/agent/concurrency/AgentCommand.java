package com.buyforu.agent.concurrency;

import java.time.Instant;
import java.util.UUID;

/**
 * PostgreSQL 中的持久化 Agent 命令。Redis 仅保存 commandId 的调度索引，不能替代该记录。
 */
public record AgentCommand(
        UUID commandId,
        String runId,
        String userId,
        CommandType commandType,
        QueueClass queueClass,
        String idempotencyKey,
        String requestHash,
        String payload,
        CommandStatus status,
        int attempts,
        Instant availableAt,
        Instant deadlineAt,
        Long executionEpoch,
        Long resultStateVersion,
        String errorCode,
        String errorDetail,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt
) {
    public enum CommandType { START, CLARIFY, SELECT, RELAX, APPROVE, REJECT, CANCEL }
    public enum QueueClass { PLANNING, TRANSACTION, CONTROL }
    public enum CommandStatus {
        QUEUED, RUNNING, RETRY_WAIT, WAITING_USER, SUCCEEDED, FAILED, CANCELLED, EXPIRED
    }
}
