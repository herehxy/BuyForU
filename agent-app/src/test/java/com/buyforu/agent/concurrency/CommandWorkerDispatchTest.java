package com.buyforu.agent.concurrency;

import com.buyforu.agent.application.GraphShoppingWorkflow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 一个派发周期必须把可用额度交给 Worker，而不是每 100ms 每个 lane 只取一条。
 * CONTROL 必须在后台流量之前处理，否则取消/拒绝会被持续的规划命令饿住。
 */
class CommandWorkerDispatchTest {
    private CommandRepository commands;
    private RunLeaseRepository leases;
    private RedisFairQueue fairQueue;
    private SimpleMeterRegistry meters;
    private CommandWorker worker;

    @BeforeEach
    void setUp() {
        commands = mock(CommandRepository.class);
        leases = mock(RunLeaseRepository.class);
        fairQueue = mock(RedisFairQueue.class);
        meters = new SimpleMeterRegistry();
        worker = new CommandWorker(commands, leases, fairQueue, mock(GraphShoppingWorkflow.class),
                mock(RunEventRepository.class), properties(), new ObjectMapper(), meters,
                new InFlightCallRegistry(meters));
    }

    @AfterEach
    void tearDown() {
        worker.close();
        meters.close();
    }

    @Test
    void fillsEveryAvailablePermitInOneBatch() {
        List<AgentCommand> queued = List.of(
                queuedCommand("run-1"), queuedCommand("run-2"), queuedCommand("run-3"));
        when(fairQueue.poll(AgentCommand.QueueClass.PLANNING))
                .thenReturn(queued.get(0).commandId(), queued.get(1).commandId(), queued.get(2).commandId());
        for (AgentCommand command : queued) {
            when(commands.find(command.commandId())).thenReturn(Optional.of(command));
        }
        when(fairQueue.tryAcquireUser(anyString(), any())).thenReturn(true);
        ExecutorService executor = mock(ExecutorService.class);
        when(executor.submit(any(Runnable.class))).thenReturn(CompletableFuture.completedFuture(null));

        Semaphore permits = new Semaphore(3);
        int dispatched = worker.dispatchBatch(AgentCommand.QueueClass.PLANNING, permits, executor, 3);

        assertEquals(3, dispatched);
        assertEquals(0, permits.availablePermits(), "三条命令应一次性占用三个 Worker 额度");
        verify(fairQueue, times(3)).poll(AgentCommand.QueueClass.PLANNING);
        verify(executor, times(3)).submit(any(Runnable.class));
    }

    @Test
    void stopsBatchInsteadOfSpinningWhenUserAlreadyHasRunningCommand() {
        AgentCommand command = queuedCommand("run-busy");
        when(fairQueue.poll(AgentCommand.QueueClass.PLANNING)).thenReturn(command.commandId());
        when(commands.find(command.commandId())).thenReturn(Optional.of(command));
        when(fairQueue.tryAcquireUser(command.userId(), command.commandId())).thenReturn(false);

        Semaphore permits = new Semaphore(2);
        int dispatched = worker.dispatchBatch(AgentCommand.QueueClass.PLANNING, permits, mock(ExecutorService.class), 2);

        assertEquals(0, dispatched);
        assertEquals(2, permits.availablePermits());
        verify(fairQueue).poll(AgentCommand.QueueClass.PLANNING);
        verify(fairQueue).enqueueFront(command);
    }

    @Test
    void dispatchProcessesControlBeforeBackgroundLanes() {
        when(commands.controlReady(anyInt())).thenReturn(List.of());
        when(fairQueue.poll(AgentCommand.QueueClass.PLANNING)).thenReturn(null);
        when(fairQueue.poll(AgentCommand.QueueClass.TRANSACTION)).thenReturn(null);

        worker.dispatch();

        InOrder order = inOrder(commands, fairQueue);
        order.verify(commands).controlReady(anyInt());
        order.verify(fairQueue).poll(AgentCommand.QueueClass.PLANNING);
        order.verify(fairQueue).poll(AgentCommand.QueueClass.TRANSACTION);
    }

    private static AgentCommand queuedCommand(String runId) {
        Instant now = Instant.now();
        return new AgentCommand(UUID.randomUUID(), runId, "user-" + runId, AgentCommand.CommandType.START,
                AgentCommand.QueueClass.PLANNING, "idem-" + runId, "hash-" + runId, "{}",
                AgentCommand.CommandStatus.QUEUED, 0, now, now.plusSeconds(210),
                null, null, null, null, now, null, null);
    }

    private static ConcurrencyProperties properties() {
        return new ConcurrencyProperties("test-instance", Duration.ofSeconds(30), Duration.ofSeconds(10),
                20, 16, 4, 20, 32, 16, 8, 1500, 500, 10, 6, 2, 30, 10, 120, 30, 100, 200);
    }
}
