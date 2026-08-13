package com.buyforu.agent.concurrency;

import com.buyforu.agent.concurrency.AgentCommand.CommandStatus;
import com.buyforu.agent.concurrency.AgentCommand.CommandType;
import com.buyforu.agent.concurrency.AgentCommand.QueueClass;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 写入口应用服务：先做分布式准入，再把命令持久化，最后建立可重建的 Redis 调度索引。
 */
@Service
public class CommandService {
    private final CommandRepository commands;
    private final RedisAdmissionController admission;
    private final RedisFairQueue fairQueue;
    private final RunEventRepository events;
    private final RunLeaseRepository leases;
    private final ObjectMapper json;

    public CommandService(CommandRepository commands, RedisAdmissionController admission, RedisFairQueue fairQueue,
                          RunEventRepository events, RunLeaseRepository leases, ObjectMapper json) {
        this.commands = commands; this.admission = admission; this.fairQueue = fairQueue;
        this.events = events; this.leases = leases; this.json = json;
    }

    public CommandAccepted accept(String runId, String userId, String remoteAddress, String idempotencyKey, CommandType type,
                                  QueueClass lane, CommandPayload payload) {
        requireIdempotencyKey(idempotencyKey);
        // 1) 先验主人，再 insert。否则别人对你的 runId 发一条 CANCEL，就能订 SSE、还能打断你的规划。
        // START 例外：runId 由 userId+key 算出来，对不上别人的任务。
        if (type != CommandType.START) assertRunOwner(runId, userId);
        String body = json.writeValueAsString(payload);
        String requestHash = sha256(type.name() + "\u001f" + runId + "\u001f" + body);
        var existing = commands.findByIdempotency(userId, runId, idempotencyKey);
        if (existing.isPresent()) return replay(existing.get(), runId, requestHash);

        if (lane == QueueClass.CONTROL && commands.pendingControlCount(userId) >= 10) {
            throw new CommandExceptions.AdmissionRejected("too many pending control commands", 5);
        }

        admission.admit(userId, remoteAddress, lane);
        Instant now = Instant.now();
        Duration total = switch (lane) {
            case PLANNING -> Duration.ofSeconds(210);
            case TRANSACTION -> Duration.ofSeconds(50);
            case CONTROL -> Duration.ofSeconds(15);
        };
        AgentCommand command = new AgentCommand(UUID.randomUUID(), runId, userId, type, lane, idempotencyKey,
                requestHash, body, CommandStatus.QUEUED, 0, now, now.plus(total), null, null,
                null, null, now, null, null);
        try {
            commands.insert(command);
        } catch (DataIntegrityViolationException race) {
            return replay(commands.findByIdempotency(userId, runId, idempotencyKey).orElseThrow(() -> race), runId, requestHash);
        }
        try {
            if (lane == QueueClass.CONTROL) {
                if (type == CommandType.CANCEL) commands.cancelPendingForRun(runId);
                leases.requestCancellation(runId);
            } else {
                fairQueue.enqueue(command);
            }
        } catch (RuntimeException rejected) {
            commands.markAdmissionRejected(command.commandId(), rejected instanceof CommandExceptions.AdmissionRejected
                    ? "QUEUE_CAPACITY_EXCEEDED" : "COORDINATION_UNAVAILABLE");
            throw rejected;
        }
        events.append(runId, command.commandId(), "command.accepted",
                java.util.Map.of("status", command.status().name(), "queueClass", lane.name()));
        if (type == CommandType.CANCEL && !commands.runStateExists(runId)) {
            // START 仍在队列且尚未创建 agent_run 时，取消持久化命令本身就是完整结果，
            // 无需让控制 Worker 去读取一个尚不存在的业务状态。
            commands.markCancelled(command.commandId(), "RUN_CANCELLED_BEFORE_START");
            events.append(runId, command.commandId(), "command.cancelled",
                    java.util.Map.of("phase", "CANCELLED"));
            return CommandAccepted.from(commands.find(command.commandId()).orElseThrow());
        }
        return CommandAccepted.from(command);
    }

    public AgentCommand get(UUID commandId, String userId) {
        return commands.findOwned(commandId, userId)
                .orElseThrow(() -> new IllegalArgumentException("command not found"));
    }

    public void assertRunOwner(String runId, String userId) {
        if (!commands.ownsRun(runId, userId)) throw new SecurityException("run belongs to another user or does not exist");
    }

    private static CommandAccepted replay(AgentCommand existing, String runId, String hash) {
        // 同一把幂等键不能拿来操作另一个 run，也不能改请求内容后重放。
        if (!existing.runId().equals(runId) || !existing.requestHash().equals(hash)) {
            throw new CommandExceptions.IdempotencyConflict();
        }
        // 失败/过期必须换新 key 再试，避免客户端一直拿到那次失败结果。
        if (existing.status() == CommandStatus.FAILED || existing.status() == CommandStatus.EXPIRED) {
            throw new CommandExceptions.IdempotencyConflict(
                    "previous command failed or expired; retry with a new Idempotency-Key");
        }
        return CommandAccepted.from(existing);
    }

    private static void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key header is required and must be at most 128 characters");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
