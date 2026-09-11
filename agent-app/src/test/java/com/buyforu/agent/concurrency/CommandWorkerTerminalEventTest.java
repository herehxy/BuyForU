package com.buyforu.agent.concurrency;

import com.buyforu.agent.application.GraphShoppingWorkflow;
import com.buyforu.agent.domain.ShoppingAgentState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 终态事件只在"确实抢到了这次状态迁移"时才上报。
 *
 * <p>{@code markSucceeded} 一类的条件更新带 {@code status='RUNNING'} 前置条件，命中 0 行意味着命令
 * 已被别的路径改写——典型是租约过期后被 {@code recoverExpired} 翻成 RETRY_WAIT，或另一实例已给出终态。
 * 修复前这些分支不看影响行数，一律 append，于是 SSE 上会出现互相矛盾的终态
 * （先 command.failed 再 command.completed），断线续传的客户端会照着错误的那条收尾。</p>
 *
 * <p>测试直接驱动 {@code dispatchLane} 并把真实的 Semaphore 与真实的单线程执行器交进去，
 * 走完整条"派发 → 领取租约 → 执行 → 终态迁移"路径，而不是只断言某个 helper。</p>
 */
class CommandWorkerTerminalEventTest {
    private CommandRepository commands;
    private RunLeaseRepository leases;
    private RedisFairQueue fairQueue;
    private GraphShoppingWorkflow workflow;
    private RunEventRepository events;
    private SimpleMeterRegistry meters;
    private CommandWorker worker;

    @BeforeEach
    void setUp() {
        commands = mock(CommandRepository.class);
        leases = mock(RunLeaseRepository.class);
        fairQueue = mock(RedisFairQueue.class);
        workflow = mock(GraphShoppingWorkflow.class);
        events = mock(RunEventRepository.class);
        meters = new SimpleMeterRegistry();
        worker = new CommandWorker(commands, leases, fairQueue, workflow, events, properties(),
                new ObjectMapper(), meters, new InFlightCallRegistry(meters));
    }

    @AfterEach
    void tearDown() {
        worker.close();
        meters.close();
    }

    @Test
    void suppressesCompletedEventWhenAnotherPathAlreadyOwnsTheTerminalState() throws Exception {
        AgentCommand command = queuedCommand();
        when(leases.claim(any(), anyString(), any())).thenReturn(Optional.of(lease(command, 7L)));
        when(workflow.start(anyString(), anyString(), anyString(), any(), anyString()))
                .thenReturn(completedState());
        // 租约已被恢复流程接管：本次执行的条件更新命不中任何 RUNNING 行。
        when(commands.markSucceeded(eq(command.commandId()), any(), any())).thenReturn(0);

        runOneCommand(command);

        verify(events, never()).append(anyString(), any(), eq("command.completed"), any());
        assertEquals(1.0, meters.counter("buyforu_terminal_event_suppressed_total",
                "event_type", "command.completed").count(),
                "被抑制的终态必须计数，否则这类竞态在线上完全不可观测");
    }

    @Test
    void suppressesFailedEventWhenAnotherPathAlreadyOwnsTheTerminalState() throws Exception {
        AgentCommand command = queuedCommand();
        when(leases.claim(any(), anyString(), any())).thenReturn(Optional.of(lease(command, 7L)));
        when(workflow.start(anyString(), anyString(), anyString(), any(), anyString()))
                .thenThrow(new IllegalStateException("planning blew up"));
        when(commands.markFailed(eq(command.commandId()), anyString(), any())).thenReturn(0);

        runOneCommand(command);

        verify(events, never()).append(anyString(), any(), eq("command.failed"), any());
        assertEquals(1.0, meters.counter("buyforu_terminal_event_suppressed_total",
                "event_type", "command.failed").count());
    }

    @Test
    void suppressesRetryWaitEventWhenTheCommandIsNoLongerRunning() throws Exception {
        AgentCommand command = queuedCommand();
        when(leases.claim(any(), anyString(), any())).thenReturn(Optional.of(lease(command, 7L)));
        when(workflow.start(anyString(), anyString(), anyString(), any(), anyString()))
                .thenThrow(new DependencyExecutor.DependencyTimeoutException(
                        DependencyExecutor.Dependency.MCP_WRITE, null));
        when(commands.retryLater(eq(command.commandId()), any(), anyString(), any())).thenReturn(0);

        runOneCommand(command);

        // 命不中 RETRY_WAIT 迁移说明命令已被恢复流程处置，此时再报"等待重试"会把客户端引向错误状态。
        verify(events, never()).append(anyString(), any(), eq("command.retry-wait"), any());
        verify(events, never()).append(anyString(), any(), eq("command.failed"), any());
    }

    @Test
    void reportsTerminalEventOnlyWhenTheTransitionWins() throws Exception {
        AgentCommand command = queuedCommand();
        when(leases.claim(any(), anyString(), any())).thenReturn(Optional.of(lease(command, 7L)));
        when(workflow.start(anyString(), anyString(), anyString(), any(), anyString()))
                .thenReturn(completedState());
        when(commands.markSucceeded(eq(command.commandId()), any(), any())).thenReturn(1);

        runOneCommand(command);

        verify(events).append(command.runId(), command.commandId(), "command.completed",
                Map.of("phase", "COMPLETED"));
        assertEquals(0.0, meters.counter("buyforu_terminal_event_suppressed_total",
                "event_type", "command.completed").count());
    }

    /**
     * 栅栏拒绝不等于命令已经结束：命令很可能已被更高 epoch 的实例接管并正在运行。
     * 因此终止迁移必须带上自己的 epoch，命不中就不能对外宣告失败。
     */
    @Test
    void doesNotFenceACommandANewerEpochAlreadyTookOver() throws Exception {
        AgentCommand command = queuedCommand();
        when(leases.claim(any(), anyString(), any())).thenReturn(Optional.of(lease(command, 7L)));
        when(workflow.start(anyString(), anyString(), anyString(), any(), anyString()))
                .thenThrow(new CommandExceptions.StaleExecution(command.runId()));
        when(commands.markFencedOut(eq(command.commandId()), any(), anyString(), any())).thenReturn(0);

        runOneCommand(command);

        verify(commands).markFencedOut(eq(command.commandId()), eq(7L), eq("STALE_EXECUTION"), anyString());
        verify(events, never()).append(anyString(), any(), eq("command.failed"), any());
        assertEquals(1.0, meters.counter("buyforu_fenced_write_rejected_total").count());
    }

    @Test
    void fencesItsOwnStillRunningCommand() throws Exception {
        AgentCommand command = queuedCommand();
        when(leases.claim(any(), anyString(), any())).thenReturn(Optional.of(lease(command, 7L)));
        when(workflow.start(anyString(), anyString(), anyString(), any(), anyString()))
                .thenThrow(new CommandExceptions.StaleExecution(command.runId()));
        when(commands.markFencedOut(eq(command.commandId()), any(), anyString(), any())).thenReturn(1);

        runOneCommand(command);

        verify(events).append(command.runId(), command.commandId(), "command.failed",
                Map.of("code", "STALE_EXECUTION"));
    }

    /** 走完整的派发路径：真实 Semaphore + 真实单线程执行器，执行结束后许可必须正好归还一次。 */
    private void runOneCommand(AgentCommand command) throws Exception {
        when(fairQueue.poll(any())).thenReturn(command.commandId());
        when(commands.find(command.commandId())).thenReturn(Optional.of(command));
        when(fairQueue.tryAcquireUser(anyString(), any())).thenReturn(true);
        Semaphore permits = new Semaphore(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            worker.dispatchLane(command.queueClass(), permits, executor);
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "worker 应在 5 秒内结束");
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, permits.availablePermits(), "许可必须正好归还一次");
    }

    private static RunLeaseRepository.Lease lease(AgentCommand command, long epoch) {
        return new RunLeaseRepository.Lease(command.commandId(), command.runId(), "test-instance", epoch,
                Instant.now().plusSeconds(30), 3L);
    }

    private static ShoppingAgentState completedState() {
        return new ShoppingAgentState("run-1", "conv-1", "user-1", "trace-1", "买一台笔记本", null,
                ShoppingAgentState.Phase.COMPLETED, List.of(), 0, null, null, null, 0, 0, 1L, null, null,
                Instant.now());
    }

    private static AgentCommand queuedCommand() {
        // 期限必须相对真实当前时间构造：写死过去的时间戳会让用例悄悄走进"已过期"分支。
        Instant now = Instant.now();
        return new AgentCommand(UUID.randomUUID(), "run-1", "user-1", AgentCommand.CommandType.START,
                AgentCommand.QueueClass.PLANNING, "idem-1", "hash-1",
                "{\"conversationId\":\"conv-1\",\"message\":\"买一台笔记本\"}",
                AgentCommand.CommandStatus.QUEUED, 0, now, now.plusSeconds(210),
                null, null, null, null, now, null, null);
    }

    private static ConcurrencyProperties properties() {
        return new ConcurrencyProperties("test-instance", Duration.ofSeconds(30), Duration.ofSeconds(10),
                20, 16, 4, 20, 32, 16, 8, 1500, 500, 10, 6, 2, 30, 10, 120, 30, 100, 200);
    }
}
