package com.buyforu.agent.concurrency;

import com.buyforu.agent.concurrency.AgentCommand.CommandStatus;
import com.buyforu.agent.concurrency.AgentCommand.CommandType;
import com.buyforu.agent.concurrency.AgentCommand.QueueClass;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 跨用户写命令和 SSE 归属必须在 insert / requestCancellation 之前失败。 */
@ExtendWith(MockitoExtension.class)
class CommandServiceOwnershipTest {
    @Mock CommandRepository commands;
    @Mock RedisAdmissionController admission;
    @Mock RedisFairQueue fairQueue;
    @Mock RunEventRepository events;
    @Mock RunLeaseRepository leases;

    private CommandService service;

    @BeforeEach
    void setUp() {
        service = new CommandService(commands, admission, fairQueue, events, leases,
                JsonMapper.builder().findAndAddModules().build());
    }

    @Test
    void rejectsCrossUserSelectionBeforeInsertOrCancel() {
        when(commands.ownsRun("run-a", "user-b")).thenReturn(false);

        assertThrows(SecurityException.class, () -> service.accept("run-a", "user-b", "127.0.0.1",
                "key-1", CommandType.SELECT, QueueClass.TRANSACTION, payload()));

        verify(commands, never()).insert(any());
        verify(leases, never()).requestCancellation(any());
        verify(fairQueue, never()).enqueue(any());
        verify(admission, never()).admit(any(), any(), any());
    }

    @Test
    void sseOwnerCheckDoesNotTreatForeignCommandAsOwnership() {
        when(commands.ownsRun("run-a", "user-b")).thenReturn(false);
        assertThrows(SecurityException.class, () -> service.assertRunOwner("run-a", "user-b"));
    }

    @Test
    void allowsFollowUpWhenOnlyStartCommandExists() {
        when(commands.ownsRun("run-a", "user-a")).thenReturn(true);
        when(commands.findByIdempotency("user-a", "run-a", "clarify-1")).thenReturn(Optional.empty());
        when(commands.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CommandAccepted accepted = service.accept("run-a", "user-a", "127.0.0.1", "clarify-1",
                CommandType.CLARIFY, QueueClass.PLANNING, payload());

        assertEquals("run-a", accepted.runId());
        verify(commands).insert(any());
        verify(fairQueue).enqueue(any());
        verify(leases, never()).requestCancellation(any());
    }

    @Test
    void requestsCancellationOnlyAfterOwnerIsConfirmed() {
        when(commands.ownsRun("run-a", "user-a")).thenReturn(true);
        when(commands.findByIdempotency("user-a", "run-a", "cancel-1")).thenReturn(Optional.empty());
        when(commands.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.accept("run-a", "user-a", "127.0.0.1", "cancel-1",
                CommandType.CANCEL, QueueClass.CONTROL, payload());

        ArgumentCaptor<AgentCommand> captor = ArgumentCaptor.forClass(AgentCommand.class);
        verify(commands).insert(captor.capture());
        verify(leases).requestCancellation(eq("run-a"));
        assertEquals(CommandType.CANCEL, captor.getValue().commandType());
        assertEquals(CommandStatus.QUEUED, captor.getValue().status());
    }

    @Test
    void startDoesNotRequireAnExistingRun() {
        when(commands.findByIdempotency("user-a", "run-new", "start-1")).thenReturn(Optional.empty());
        when(commands.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.accept("run-new", "user-a", "127.0.0.1", "start-1",
                CommandType.START, QueueClass.PLANNING, payload());

        verify(commands, never()).ownsRun(any(), any());
        verify(commands).insert(any());
    }

    @Test
    void rejectsIdempotentReplayBoundToAnotherRun() {
        AgentCommand existing = new AgentCommand(UUID.randomUUID(), "run-other", "user-a", CommandType.SELECT,
                QueueClass.TRANSACTION, "same-key", "deadbeef", "{}", CommandStatus.QUEUED, 0,
                Instant.parse("2026-08-12T08:00:00Z"), Instant.parse("2026-08-12T08:01:00Z"),
                null, null, null, null, Instant.parse("2026-08-12T08:00:00Z"), null, null);
        when(commands.ownsRun("run-a", "user-a")).thenReturn(true);
        when(commands.findByIdempotency("user-a", "run-a", "same-key")).thenReturn(Optional.of(existing));

        assertThrows(CommandExceptions.IdempotencyConflict.class, () -> service.accept("run-a", "user-a",
                "127.0.0.1", "same-key", CommandType.SELECT, QueueClass.TRANSACTION, payload()));
        verify(commands, never()).insert(any());
    }

    private static CommandPayload payload() {
        return new CommandPayload(null, "hello", "sku-1", null, null, null, null);
    }
}
