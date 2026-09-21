package com.buyforu.agent.concurrency;

import com.buyforu.agent.application.GraphShoppingWorkflow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 租约恢复的用户许可顺序。
 *
 * <p>不变量：{@code releaseUser} 必须发生在状态被翻回可派发状态之前。</p>
 *
 * <p>顺序颠倒的后果：{@code recoverExpired} 把命令改成 RETRY_WAIT 且 {@code available_at=now()}，
 * 100 毫秒后的派发周期就能捞到它；此时 Redis 里的用户许可仍被上一任执行占着（TTL 240 秒），
 * 于是命令被塞回队首、下个周期再弹一次，同一个用户被自己的旧执行堵住最长 4 分钟。</p>
 *
 * <p>修复前还有第二重问题：它靠 {@code error_code + 15 秒时间窗} 反查"刚恢复的命令"来释放许可，
 * 既可能漏（窗口外）也可能误伤（release 掉别的实例刚恢复的命令）。</p>
 */
class CommandWorkerRecoveryTest {
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
        worker = new CommandWorker(commands, leases, fairQueue, mock(GraphShoppingWorkflow.class), null,
                properties(), new ObjectMapper(), meters, new InFlightCallRegistry(meters));
    }

    @AfterEach
    void tearDown() {
        worker.close();
        meters.close();
    }

    @Test
    void releasesUserPermitsBeforeTheCommandCanBeDispatchedAgain() {
        AgentCommand orphan = runningCommand();
        when(commands.recoverableCommands(anyInt())).thenReturn(List.of(orphan));
        when(leases.recoverExpired()).thenReturn(1);

        worker.recoverExpiredLeases();

        InOrder order = inOrder(fairQueue, leases);
        order.verify(fairQueue).releaseUser(orphan.userId(), orphan.commandId());
        order.verify(leases).recoverExpired();
        assertEquals(1.0, meters.counter("buyforu_lease_recovered_total").count());
    }

    @Test
    void releasesNothingWhenNoCommandLostItsLease() {
        when(commands.recoverableCommands(anyInt())).thenReturn(List.of());
        when(leases.recoverExpired()).thenReturn(0);

        worker.recoverExpiredLeases();

        verify(fairQueue, never()).releaseUser(anyString(), any());
        assertEquals(0.0, meters.counter("buyforu_lease_recovered_total").count());
    }

    /**
     * Redis 抖不能挡住状态恢复：PostgreSQL 才是真相来源，翻状态必须照做，
     * 队列索引由 reconcileRedisIndex 事后重建。每一条命令的释放失败都只影响它自己。
     */
    @Test
    void stillRecoversWhenReleasingAUserPermitFails() {
        AgentCommand orphan = runningCommand();
        when(commands.recoverableCommands(anyInt())).thenReturn(List.of(orphan));
        doThrow(new IllegalStateException("redis unavailable"))
                .when(fairQueue).releaseUser(anyString(), any());

        worker.recoverExpiredLeases();

        verify(leases).recoverExpired();
    }

    /** 恢复是定时任务：数据库不可用时必须自己吞掉异常，否则整个调度线程会被反复打断。 */
    @Test
    void swallowsDatabaseFailureInsteadOfPropagatingIntoTheScheduler() {
        when(commands.recoverableCommands(anyInt()))
                .thenThrow(new DataAccessResourceFailureException("pool exhausted"));

        assertDoesNotThrow(worker::recoverExpiredLeases);
    }

    private static AgentCommand runningCommand() {
        Instant now = Instant.now();
        return new AgentCommand(UUID.randomUUID(), "run-1", "user-1", AgentCommand.CommandType.START,
                AgentCommand.QueueClass.PLANNING, "idem-1", "hash-1", "{}",
                AgentCommand.CommandStatus.RUNNING, 1, now, now.plusSeconds(210),
                null, null, "WORKER_LEASE_EXPIRED", null, now, now, null);
    }

    private static ConcurrencyProperties properties() {
        return new ConcurrencyProperties("test-instance", Duration.ofSeconds(30), Duration.ofSeconds(10),
                20, 16, 4, 20, 32, 16, 8, 1500, 500, 10, 6, 2, 30, 10, 120, 30, 100, 200);
    }
}
