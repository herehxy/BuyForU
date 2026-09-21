package com.buyforu.agent.api;

import com.buyforu.agent.concurrency.RunEventRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.buyforu.agent.concurrency.RunEventNotifier;
import com.buyforu.agent.concurrency.CommandService;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 可断点续传的 SSE。每次查询都在 JdbcTemplate 返回后再写网络，数据库连接不会跨越慢客户端发送。
 */
@RestController
@RequestMapping("/api/v1/runs")
public class RunEventController {
    /**
     * 心跳间隔必须明显小于前端的 SSE 读超时，否则健康连接会被前端判成"代理缓冲了 SSE"
     * 并永久降级到轮询。历史上这里和前端一样是 15 秒，两边相等会让每次心跳都与超时赛跑。
     * 当前约定：服务端 10 秒心跳，前端 25 秒读超时。
     */
    private static final long HEARTBEAT_INTERVAL_MS = 10_000;
    private final RunEventRepository events;
    private final RunEventNotifier notifier;
    private final CommandService commands;
    private final Semaphore connectionPermits = new Semaphore(2000);
    private final MeterRegistry meters;
    private final ExecutorService streams = Executors.newVirtualThreadPerTaskExecutor();

    public RunEventController(RunEventRepository events, RunEventNotifier notifier,
                              CommandService commands, MeterRegistry meters) {
        this.events = events; this.notifier = notifier; this.commands = commands; this.meters = meters;
        meters.gauge("buyforu_sse_connections", connectionPermits,
                permits -> 2000 - permits.availablePermits());
    }

    @GetMapping(path = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@AuthenticationPrincipal Jwt jwt, @PathVariable String runId,
                      @RequestHeader(value = "Last-Event-ID", defaultValue = "0") long lastEventId) {
        // 只认 run 主人（agent_run 或 START 命令），不认“我是否写过任意命令”。
        commands.assertRunOwner(runId, AuthenticatedUser.id(jwt));
        if (!connectionPermits.tryAcquire()) {
            throw new com.buyforu.agent.concurrency.CommandExceptions.AdmissionRejected(
                    "SSE connection capacity exceeded", 5);
        }
        if (lastEventId < 0) {
            connectionPermits.release();
            throw new IllegalArgumentException("Last-Event-ID cannot be negative");
        }
        notifier.retain(runId);
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                notifier.release(runId);
                connectionPermits.release();
            }
        };
        emitter.onCompletion(release); emitter.onTimeout(release); emitter.onError(ignored -> release.run());
        streams.submit(() -> publish(runId, lastEventId, emitter));
        return emitter;
    }

    private void publish(String runId, long cursor, SseEmitter emitter) {
        long lastHeartbeat = 0;
        try {
            while (true) {
                long version = notifier.version(runId);
                var batch = events.after(runId, cursor, 100);
                for (var event : batch) {
                    emitter.send(SseEmitter.event().id(Long.toString(event.eventId())).name(event.eventType())
                            .data(Map.of("commandId", event.commandId(), "payload", event.payload())));
                    cursor = event.eventId();
                }
                long now = System.currentTimeMillis();
                if (now - lastHeartbeat >= HEARTBEAT_INTERVAL_MS) {
                    emitter.send(SseEmitter.event().name("heartbeat").data(Map.of("time", now)));
                    lastHeartbeat = now;
                }
                // 没有新事件时由本地/Redis 通知唤醒；心跳间隔一到就发一次保活帧。
                if (batch.isEmpty()) notifier.await(runId, version, Duration.ofMillis(HEARTBEAT_INTERVAL_MS));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); emitter.complete();
        } catch (Exception disconnected) {
            meters.counter("buyforu_sse_delivery_failures_total").increment();
            emitter.complete();
        }
    }

    @PreDestroy void close() { streams.close(); }
}
