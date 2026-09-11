package com.buyforu.agent.concurrency;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/** Redis Pub/Sub 只负责跨实例唤醒 SSE；失败时事件仍已安全存于 PostgreSQL。 */
@Component
public class RunEventNotifier implements MessageListener {
    public static final String CHANNEL = "buyforu:run-events";
    private final StringRedisTemplate redis;
    private final ConcurrentHashMap<String, Signal> signals = new ConcurrentHashMap<>();

    public RunEventNotifier(StringRedisTemplate redis) { this.redis = redis; }

    public void publish(String runId) {
        signalIfObserved(runId);
        try { redis.convertAndSend(CHANNEL, runId); } catch (RuntimeException ignored) { }
    }

    /** SSE 建连时登记观察者；最后一个连接退出后删除本地 signal，避免 runId 长期累积。 */
    public void retain(String runId) {
        signals.compute(runId, (ignored, existing) -> {
            Signal signal = existing == null ? new Signal() : existing;
            signal.observers.incrementAndGet();
            return signal;
        });
    }

    public void release(String runId) {
        signals.computeIfPresent(runId, (ignored, signal) ->
                signal.observers.decrementAndGet() <= 0 ? null : signal);
    }

    public long version(String runId) {
        Signal signal = signals.get(runId);
        return signal == null ? 0 : signal.version.get();
    }

    public void await(String runId, long version, Duration timeout) throws InterruptedException {
        Signal signal = signals.computeIfAbsent(runId, ignored -> new Signal());
        synchronized (signal) {
            if (signal.version.get() == version) signal.wait(timeout.toMillis());
        }
    }

    @Override public void onMessage(Message message, byte[] pattern) {
        signalIfObserved(new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8));
    }

    private void signalIfObserved(String runId) {
        Signal signal = signals.get(runId);
        if (signal == null) return;
        synchronized (signal) { signal.version.incrementAndGet(); signal.notifyAll(); }
    }

    private static final class Signal {
        private final AtomicLong version = new AtomicLong();
        private final AtomicInteger observers = new AtomicInteger();
    }
}
