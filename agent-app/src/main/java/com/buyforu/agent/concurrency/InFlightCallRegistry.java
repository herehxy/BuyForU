package com.buyforu.agent.concurrency;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/** 记下每个命令当前正在等的下游 Future，取消时直接 cancel(true)，而不是只打断外层线程。 */
@Component
public class InFlightCallRegistry {
    private final ConcurrentHashMap<UUID, Future<?>> calls = new ConcurrentHashMap<>();

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
