package com.buyforu.agent.concurrency;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * DeepSeek、MCP 和 Embedding 的统一隔离层：独立虚拟线程执行器、零等待 Bulkhead、硬超时和独立熔断器。
 */
@Component
public final class DependencyExecutor {
    public enum Dependency { DEEPSEEK, MCP_READ, MCP_WRITE, EMBEDDING }

    private final ExecutorService deepseek = executor("deepseek-");
    private final ExecutorService mcpRead = executor("mcp-read-");
    private final ExecutorService mcpWrite = executor("mcp-write-");
    private final ExecutorService embedding = executor("embedding-");
    private final Map<Dependency, Bulkhead> bulkheads;
    private final Map<Dependency, CircuitBreaker> breakers;

    public DependencyExecutor(ConcurrencyProperties properties, MeterRegistry meters) {
        bulkheads = Map.of(
                Dependency.DEEPSEEK, bulkhead("deepseek-planning", properties.deepseekConcurrency()),
                Dependency.MCP_READ, bulkhead("commerce-mcp-read", properties.mcpReadConcurrency()),
                Dependency.MCP_WRITE, bulkhead("commerce-mcp-write", properties.mcpWriteConcurrency()),
                Dependency.EMBEDDING, bulkhead("ollama-embedding", properties.embeddingConcurrency()));
        breakers = Map.of(
                Dependency.DEEPSEEK, breaker("deepseek-planning", 20, 10, 50, 30, 30, 3),
                Dependency.MCP_READ, breaker("commerce-mcp-read", 50, 20, 50, 2, 10, 5),
                Dependency.MCP_WRITE, breaker("commerce-mcp-write", 20, 10, 40, 3, 15, 2),
                Dependency.EMBEDDING, breaker("ollama-embedding", 20, 10, 50, 5, 20, 3));
        breakers.forEach((dependency, breaker) -> meters.gauge("buyforu_circuit_state",
                java.util.List.of(io.micrometer.core.instrument.Tag.of("name", breaker.getName())), breaker,
                value -> value.getState().getOrder()));
        bulkheads.forEach((dependency, bulkhead) -> meters.gauge("buyforu_dependency_active",
                java.util.List.of(io.micrometer.core.instrument.Tag.of("name", bulkhead.getName())), bulkhead,
                value -> value.getMetrics().getMaxAllowedConcurrentCalls()
                        - value.getMetrics().getAvailableConcurrentCalls()));
    }

    public <T> T call(Dependency dependency, Duration timeout, int attempts, Callable<T> action) {
        NetworkCallGuard.assertNoTransaction(dependency.name());
        Instant commandDeadline = ExecutionContext.current() == null ? null : ExecutionContext.current().deadlineAt();
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            Duration effective = remaining(timeout, commandDeadline);
            if (effective.isZero() || effective.isNegative()) throw new DependencyTimeoutException(dependency, null);
            try {
                Callable<T> protectedCall = Bulkhead.decorateCallable(bulkheads.get(dependency),
                        CircuitBreaker.decorateCallable(breakers.get(dependency), action));
                Future<T> future = executor(dependency).submit(protectedCall);
                try {
                    return future.get(effective.toMillis(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException timeoutFailure) {
                    future.cancel(true);
                    breakers.get(dependency).onError(effective.toNanos(), TimeUnit.NANOSECONDS, timeoutFailure);
                    throw new DependencyTimeoutException(dependency, timeoutFailure);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new DependencyInterruptedException(dependency, interrupted);
            } catch (ExecutionException execution) {
                Throwable cause = execution.getCause();
                if (cause instanceof RuntimeException runtime) last = runtime;
                else last = new IllegalStateException(cause);
            }
            if (attempt < attempts && retryable(last)) continue;
            throw last;
        }
        throw last == null ? new IllegalStateException("dependency call failed") : last;
    }

    private static boolean retryable(RuntimeException failure) {
        if (failure instanceof IllegalArgumentException) return false;
        String name = failure.getClass().getSimpleName();
        return name.contains("Timeout") || name.contains("Connect") || name.contains("Transport")
                || name.contains("ResourceAccess") || name.contains("WebClient");
    }

    private static Duration remaining(Duration configured, Instant deadline) {
        if (deadline == null) return configured;
        Duration remaining = Duration.between(Instant.now(), deadline);
        return remaining.compareTo(configured) < 0 ? remaining : configured;
    }

    private ExecutorService executor(Dependency dependency) {
        return switch (dependency) {
            case DEEPSEEK -> deepseek;
            case MCP_READ -> mcpRead;
            case MCP_WRITE -> mcpWrite;
            case EMBEDDING -> embedding;
        };
    }

    private static ExecutorService executor(String prefix) {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(prefix, 0).factory());
    }

    private static Bulkhead bulkhead(String name, int permits) {
        return Bulkhead.of(name, BulkheadConfig.custom().maxConcurrentCalls(permits)
                .maxWaitDuration(Duration.ZERO).build());
    }

    private static CircuitBreaker breaker(String name, int window, int minimum, float failures,
                                          int slowSeconds, int openSeconds, int halfOpen) {
        Predicate<Throwable> infrastructureFailure = failure -> !(failure instanceof IllegalArgumentException)
                && !failure.getClass().getName().contains("CommerceOperationException");
        return CircuitBreaker.of(name, CircuitBreakerConfig.custom().slidingWindowSize(window)
                .minimumNumberOfCalls(minimum).failureRateThreshold(failures)
                .slowCallDurationThreshold(Duration.ofSeconds(slowSeconds)).slowCallRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(openSeconds))
                .permittedNumberOfCallsInHalfOpenState(halfOpen).recordException(infrastructureFailure).build());
    }

    @PreDestroy void close() { deepseek.close(); mcpRead.close(); mcpWrite.close(); embedding.close(); }

    public static final class DependencyTimeoutException extends RuntimeException {
        DependencyTimeoutException(Dependency dependency, Throwable cause) {
            super(dependency + " timed out", cause);
        }
    }
    public static final class DependencyInterruptedException extends RuntimeException {
        DependencyInterruptedException(Dependency dependency, Throwable cause) {
            super(dependency + " was cancelled", cause);
        }
    }
}
