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
        String body = json.writeValueAsString(payload);
        String requestHash = sha256(type.name() + "\u001f" + runId + "\u001f" + body);
        var existing = commands.findByIdempotency(userId, idempotencyKey);
        if (existing.isPresent()) return replay(existing.get(), requestHash);

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
            return replay(commands.findByIdempotency(userId, idempotencyKey).orElseThrow(() -> race), requestHash);
        }
        try {
            if (lane == QueueClass.CONTROL) {
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
        return CommandAccepted.from(command);
    }

    public AgentCommand get(UUID commandId, String userId) {
        return commands.findOwned(commandId, userId)
                .orElseThrow(() -> new IllegalArgumentException("command not found"));
    }

    public void assertRunOwner(String runId, String userId) {
        if (!commands.ownsRun(runId, userId)) throw new SecurityException("run belongs to another user or does not exist");
    }

    private static CommandAccepted replay(AgentCommand existing, String hash) {
        if (!existing.requestHash().equals(hash)) throw new CommandExceptions.IdempotencyConflict();
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
