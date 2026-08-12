package com.buyforu.agent.concurrency;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 当前 Worker 的栅栏信息。只在线程执行范围内存在，持久化层据此拒绝租约过期的旧执行器。
 */
public final class ExecutionContext {
    private static final ThreadLocal<ExecutionContext> CURRENT = new ThreadLocal<>();
    private final UUID commandId;
    private final String runId;
    private final long epoch;
    private final Instant deadlineAt;
    private final AtomicLong expectedStateVersion;

    public ExecutionContext(UUID commandId, String runId, long epoch, Instant deadlineAt, long stateVersion) {
        this.commandId = commandId; this.runId = runId; this.epoch = epoch; this.deadlineAt = deadlineAt;
        this.expectedStateVersion = new AtomicLong(stateVersion);
    }

    public UUID commandId() { return commandId; }
    public String runId() { return runId; }
    public long epoch() { return epoch; }
    public Instant deadlineAt() { return deadlineAt; }
    public long expectedStateVersion() { return expectedStateVersion.get(); }
    public void stateSaved() { expectedStateVersion.incrementAndGet(); }

    public static ExecutionContext current() { return CURRENT.get(); }

    public static <T> T call(ExecutionContext context, Supplier<T> action) {
        ExecutionContext previous = CURRENT.get();
        CURRENT.set(context);
        try {
            return action.get();
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    public static void run(ExecutionContext context, Runnable action) {
        call(context, () -> { action.run(); return null; });
    }
}
