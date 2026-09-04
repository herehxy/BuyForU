package com.buyforu.agent.concurrency;

/** 入口治理异常集中在同一文件，便于 API 层稳定映射 409/429/503。 */
public final class CommandExceptions {
    private CommandExceptions() { }

    public static final class IdempotencyConflict extends RuntimeException {
        public IdempotencyConflict() { super("Idempotency-Key was reused with a different request"); }
        public IdempotencyConflict(String message) { super(message); }
    }

    public static final class AdmissionRejected extends RuntimeException {
        private final long retryAfterSeconds;
        public AdmissionRejected(String message, long retryAfterSeconds) {
            super(message);
            this.retryAfterSeconds = retryAfterSeconds;
        }
        public long retryAfterSeconds() { return retryAfterSeconds; }
    }

    public static final class CoordinationUnavailable extends RuntimeException {
        public CoordinationUnavailable(Throwable cause) { super("traffic coordination is temporarily unavailable", cause); }
    }

    public static final class StaleExecution extends RuntimeException {
        public StaleExecution(String runId) { super("stale worker was fenced for run " + runId); }
    }
}
