package com.buyforu.agent.concurrency;

import com.buyforu.agent.application.GraphShoppingWorkflow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 派发路径的许可账目。
 *
 * 核心不变量：{@code permits.tryAcquire()} 成功之后，许可必须"要么由 dispatchLane 归还，
 * 要么移交给 execute 归还"，且只归还一次。
 *
 * 修复前 dispatchLane 只在几条正常分支上 release，commands.find / markExpired /
 * enqueueFront / executor.submit 抛异常时都不归还。配合 1 秒的连接超时（快速失败是刻意设计），
 * 负载下瞬时连接失败属于预期事件，每次都会永久吃掉一个并发额度：PLANNING 20 / TRANSACTION 16 /
 * CONTROL 4 被蚕食到 0 后整条 lane 停止派发，只能重启进程恢复。
 *
 * 这里把真实的 Semaphore 交进去再数一遍——静态断言看不出"少还一个"或"多还一个"。
 */
class CommandWorkerPermitTest {
    private static final int PERMITS = 2;

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
    void returnsPermitWhenCommandLookupFails() {
        UUID commandId = UUID.randomUUID();
        when(fairQueue.poll(any())).thenReturn(commandId);
        // Hikari 连接超时耗尽时 commands.find 抛的就是这一类异常。
        when(commands.find(commandId)).thenThrow(new DataAccessResourceFailureException("pool exhausted"));

        Semaphore permits = new Semaphore(PERMITS);
        worker.dispatchLane(AgentCommand.QueueClass.PLANNING, permits, unusedExecutor());

        assertEquals(PERMITS, permits.availablePermits(),
                "查询命令失败也必须归还许可，否则每次瞬时故障都永久少一个并发额度");
        assertEquals(1.0, meters.counter("buyforu_dispatch_failure_total",
                "queue_class", "PLANNING").count());
    }

    @Test
    void returnsPermitWhenQueuePollFailsSilently() {
        when(fairQueue.poll(any())).thenThrow(new IllegalStateException("redis unavailable"));

        Semaphore permits = new Semaphore(PERMITS);
        worker.dispatchLane(AgentCommand.QueueClass.PLANNING, permits, unusedExecutor());

        assertEquals(PERMITS, permits.availablePermits(), "Redis 抖动同样不能吃许可");
        // 协调层抖动是预期降级，按每个派发周期计一次失败会让告警失去意义。
        assertEquals(0.0, meters.counter("buyforu_dispatch_failure_total",
                "queue_class", "PLANNING").count());
    }

    @Test
    void returnsPermitWhenUserAlreadyHasARunningCommand() {
        AgentCommand command = queuedCommand(AgentCommand.QueueClass.PLANNING);
        when(fairQueue.poll(any())).thenReturn(command.commandId());
        when(commands.find(command.commandId())).thenReturn(Optional.of(command));
        when(fairQueue.tryAcquireUser(anyString(), any())).thenReturn(false);

        Semaphore permits = new Semaphore(PERMITS);
        worker.dispatchLane(AgentCommand.QueueClass.PLANNING, permits, unusedExecutor());

        assertEquals(PERMITS, permits.availablePermits());
        verify(fairQueue).enqueueFront(command);
    }

    @Test
    void returnsPermitWhenExecutorRejectsSubmission() {
        AgentCommand command = queuedCommand(AgentCommand.QueueClass.PLANNING);
        when(fairQueue.poll(any())).thenReturn(command.commandId());
        when(commands.find(command.commandId())).thenReturn(Optional.of(command));
        when(fairQueue.tryAcquireUser(anyString(), any())).thenReturn(true);
        ExecutorService rejecting = mock(ExecutorService.class);
        when(rejecting.submit(any(Runnable.class))).thenThrow(new RejectedExecutionException("shutting down"));

        Semaphore permits = new Semaphore(PERMITS);
        worker.dispatchLane(AgentCommand.QueueClass.PLANNING, permits, rejecting);

        assertEquals(PERMITS, permits.availablePermits(),
                "线程池拒绝执行时许可仍归调用方，必须原地归还");
        // 证明用例确实走到了提交这一步，而不是被"命令已过期"分支提前拦下。
        verify(rejecting).submit(any(Runnable.class));
    }

    @Test
    void handsPermitOverExactlyOnceOnSuccess() throws Exception {
        AgentCommand command = queuedCommand(AgentCommand.QueueClass.PLANNING);
        when(fairQueue.poll(any())).thenReturn(command.commandId());
        when(commands.find(command.commandId())).thenReturn(Optional.of(command));
        when(fairQueue.tryAcquireUser(anyString(), any())).thenReturn(true);
        // claim 返回空租约，execute 会在 finally 里归还许可后立即退出。
        when(leases.claim(any(), anyString(), any())).thenReturn(Optional.empty());

        Semaphore permits = new Semaphore(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            worker.dispatchLane(AgentCommand.QueueClass.PLANNING, permits, executor);
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "worker 应在 5 秒内结束");
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, permits.availablePermits(),
                "execute 归还一次；dispatchLane 不得再还一次，否则额度会被放大");
        // 证明许可确实被移交给 execute（它进入执行路径并尝试领取租约）。
        verify(leases).claim(any(), anyString(), any());
    }

    private static ExecutorService unusedExecutor() {
        return mock(ExecutorService.class);
    }

    private static AgentCommand queuedCommand(AgentCommand.QueueClass lane) {
        // 期限必须相对真实当前时间构造：写死一个过去的时间戳会让用例悄悄走进
        // "命令已过期"分支，测试看着是绿的却什么都没验证。
        Instant now = Instant.now();
        return new AgentCommand(UUID.randomUUID(), "run-1", "user-1", AgentCommand.CommandType.START, lane,
                "idem-1", "hash-1", "{}", AgentCommand.CommandStatus.QUEUED, 0, now, now.plusSeconds(210),
                null, null, null, null, now, null, null);
    }

    private static ConcurrencyProperties properties() {
        return new ConcurrencyProperties("test-instance", Duration.ofSeconds(30), Duration.ofSeconds(10),
                20, 16, 4, 20, 32, 16, 8, 1500, 500, 10, 6, 2, 30, 10, 120, 30, 100, 200);
    }
}
