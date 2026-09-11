package com.buyforu.agent.concurrency;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 受理路径的降级语义。
 *
 * <p>不变量：命令一旦落库、并已进入（或在）全序控制，写 SSE 事件失败就不能把 202 变成 500。
 * 走到那一步时客户端重试还会命中幂等键；抛 500 只会让客户端以为命令没被受理，
 * 而它其实已经在跑。丢的只是一个 SSE 锚点，状态机不依赖它。</p>
 */
class CommandServiceAcceptTest {
    private CommandRepository commands;
    private RedisAdmissionController admission;
    private RedisFairQueue fairQueue;
    private RunEventRepository events;
    private RunLeaseRepository leases;
    private SimpleMeterRegistry meters;
    private CommandService service;
    private final AtomicReference<AgentCommand> inserted = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        commands = mock(CommandRepository.class);
        admission = mock(RedisAdmissionController.class);
        fairQueue = mock(RedisFairQueue.class);
        events = mock(RunEventRepository.class);
        leases = mock(RunLeaseRepository.class);
        meters = new SimpleMeterRegistry();
        service = new CommandService(commands, admission, fairQueue, events, leases, new ObjectMapper(), meters);
        when(commands.findByIdempotency(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(commands.insert(any(AgentCommand.class))).thenAnswer(invocation -> {
            AgentCommand command = invocation.getArgument(0);
            inserted.set(command);
            return command;
        });
        when(commands.find(any())).thenAnswer(invocation -> Optional.ofNullable(inserted.get()));
    }

    @AfterEach
    void tearDown() {
        meters.close();
    }

    @Test
    void stillAcceptsTheCommandWhenTheAcceptedEventCannotBeWritten() {
        doThrow(new IllegalStateException("agent_run_event unavailable"))
                .when(events).append(anyString(), any(), anyString(), any());

        CommandAccepted accepted = service.accept("run-1", "user-1", "127.0.0.1", "idem-1",
                AgentCommand.CommandType.START, AgentCommand.QueueClass.PLANNING, payload());

        assertNotNull(accepted);
        assertEquals(inserted.get().commandId(), accepted.commandId());
        verify(commands).insert(any(AgentCommand.class));
        verify(fairQueue).enqueue(any(AgentCommand.class));
        assertEquals(1.0, meters.counter("buyforu_run_event_append_failed_total",
                "event_type", "command.accepted").count(),
                "被吞掉的事件写失败必须计数，否则受理降级在线上完全不可见");
    }

    @Test
    void stillReturnsTheCancellationWhenItsEventCannotBeWritten() {
        when(commands.ownsRun("run-1", "user-1")).thenReturn(true);
        when(commands.runStateExists("run-1")).thenReturn(false);
        when(commands.markCancelled(any(UUID.class), anyString())).thenReturn(1);
        doThrow(new IllegalStateException("agent_run_event unavailable"))
                .when(events).append(anyString(), any(), anyString(), any());

        CommandAccepted accepted = service.accept("run-1", "user-1", "127.0.0.1", "idem-2",
                AgentCommand.CommandType.CANCEL, AgentCommand.QueueClass.CONTROL, payload());

        assertEquals(inserted.get().commandId(), accepted.commandId());
        // 取消标记已经写进 PostgreSQL，控制 Worker 会据它中断正在跑的规划——这部分不受事件影响。
        verify(leases).requestCancellation("run-1");
        verify(commands).markCancelled(any(UUID.class), eq("RUN_CANCELLED_BEFORE_START"));
        assertEquals(1.0, meters.counter("buyforu_run_event_append_failed_total",
                "event_type", "command.cancelled").count());
    }

    @Test
    void appendsTheAcceptedEventOnTheHappyPath() {
        service.accept("run-1", "user-1", "127.0.0.1", "idem-3", AgentCommand.CommandType.START,
                AgentCommand.QueueClass.PLANNING, payload());

        verify(events).append(eq("run-1"), any(UUID.class), eq("command.accepted"), any());
        assertEquals(0.0, meters.counter("buyforu_run_event_append_failed_total",
                "event_type", "command.accepted").count());
    }

    private static CommandPayload payload() {
        return new CommandPayload("conv-1", "买一台笔记本", null, null, null, null, null);
    }
}
