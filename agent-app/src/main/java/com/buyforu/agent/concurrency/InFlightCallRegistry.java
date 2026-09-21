package com.buyforu.agent.concurrency;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * 记下每个命令当前正在等的下游 Future，取消时直接 cancel(true)，而不是只打断外层线程。
 *
 * <p>这里没有 TTL 也没有兜底清理：正常路径由 {@code DependencyExecutor} 在 finally 里 clear，
 * 但只要有一条路径漏掉，条目就会永久留在表里。在没有 reaper 之前，先把在册数量暴露成指标——
 * 它必须随命令结束回落到 0，只增不减就说明存在漏清理，能在内存问题之前被发现。</p>
 */
@Component
public class InFlightCallRegistry {
    private final ConcurrentHashMap<UUID, Future<?>> calls = new ConcurrentHashMap<>();

    public InFlightCallRegistry(MeterRegistry meters) {
        Gauge.builder("buyforu_inflight_calls", calls, Map::size).register(meters);
    }

    public void register(UUID commandId, Future<?> future) {
        calls.put(commandId, future);
    }

    public void clear(UUID commandId, Future<?> future) {
        calls.remove(commandId, future);
    }

    public boolean cancel(UUID commandId) {
        Future<?> future = calls.get(commandId);
        return future != null && future.cancel(true);
    }
}
