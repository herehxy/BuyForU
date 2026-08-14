package com.buyforu.agent.concurrency;

import com.buyforu.agent.application.GraphShoppingWorkflow;
import com.buyforu.agent.domain.ShoppingAgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ConcurrentHashMap;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.buyforu.commerce.port.CommerceOperationException;

/**
 * 公平队列消费者。领取租约的事务在提交后才把任务交给执行器，因此网络等待不占数据库连接。
 */
@Component
public class CommandWorker {
    private static final Logger log = LoggerFactory.getLogger(CommandWorker.class);
    private final CommandRepository commands;
    private final RunLeaseRepository leases;
    private final RedisFairQueue fairQueue;
    private final GraphShoppingWorkflow workflow;
    private final RunEventRepository events;
    private final ConcurrencyProperties properties;
    private final ObjectMapper json;
    private final MeterRegistry meters;
    private final ExecutorService planning = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService transaction = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService control = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore planningPermits;
    private final Semaphore transactionPermits;
    private final Semaphore controlPermits;
    private final Map<UUID, RunLeaseRepository.Lease> activeLeases = new ConcurrentHashMap<>();
    private final Map<UUID, Thread> activeThreads = new ConcurrentHashMap<>();
    private final InFlightCallRegistry inFlight;

    public CommandWorker(CommandRepository commands, RunLeaseRepository leases, RedisFairQueue fairQueue,
                         GraphShoppingWorkflow workflow, RunEventRepository events,
                         ConcurrencyProperties properties, ObjectMapper json, MeterRegistry meters,
                         InFlightCallRegistry inFlight) {
        this.commands = commands; this.leases = leases; this.fairQueue = fairQueue; this.workflow = workflow;
        this.events = events; this.properties = properties; this.json = json;
        this.meters = meters;
        this.inFlight = inFlight;
        this.planningPermits = new Semaphore(properties.planningWorkers());
        this.transactionPermits = new Semaphore(properties.transactionWorkers());
        this.controlPermits = new Semaphore(properties.controlWorkers());
    }

    @Scheduled(fixedDelay = 100, scheduler = "dispatchScheduler")
    void dispatch() {
        dispatchLane(AgentCommand.QueueClass.PLANNING, planningPermits, planning);
        dispatchLane(AgentCommand.QueueClass.TRANSACTION, transactionPermits, transaction);
        // 一次取多条可执行的 CONTROL，跳过租约还被占用的 run。
        for (AgentCommand command : commands.controlReady(properties.controlWorkers())) {
            if (!controlPermits.tryAcquire()) break;
            control.submit(() -> execute(command, controlPermits, false));
        }
    }

    @Scheduled(fixedDelay = 5000, scheduler = "maintenanceScheduler")
    void reconcileRedisIndex() {
        for (var lane : new AgentCommand.QueueClass[]{AgentCommand.QueueClass.PLANNING,
                AgentCommand.QueueClass.TRANSACTION}) {
            // 队列上限合计 2000，整表扫一遍即可，Lua 入队会去重，不必再做索引状态表。
            for (AgentCommand command : commands.queuedWithoutIndex(lane, 2000)) {
                if (command.deadlineAt().isBefore(Instant.now())) commands.markExpired(command.commandId());
                else try { fairQueue.enqueue(command); } catch (RuntimeException failure) {
                    log.warn("Could not rebuild fair queue index for command {}", command.commandId(), failure);
                    break;
                }
            }
        }
    }

    /** 续租使用独立短事务，绝不和正在进行的网络调用共享连接。 */
    @Scheduled(fixedDelayString = "${buyforu.concurrency.lease-heartbeat:10s}", scheduler = "leaseScheduler")
    void heartbeat() {
        Instant staleBefore = Instant.now().minusSeconds(90);
        activeLeases.forEach((commandId, lease) -> {
            if (leases.cancellationRequested(lease)) {
                inFlight.cancel(commandId);
                Thread worker = activeThreads.get(commandId);
                if (worker != null) worker.interrupt();
            }
            commands.find(commandId).ifPresent(command -> {
                // 心跳不能无限续租一个已经卡住的 DeepSeek 调用，否则前端会一直 RUNNING。
                if (command.startedAt() != null && command.startedAt().isBefore(staleBefore)) {
                    inFlight.cancel(commandId);
                    Thread worker = activeThreads.get(commandId);
                    if (worker != null) worker.interrupt();
                    return;
                }
            });
            if (!leases.heartbeat(lease, Instant.now().plus(properties.leaseDuration()))) {
                activeLeases.remove(commandId);
            }
        });
    }

    @Scheduled(fixedDelay = 3600000, scheduler = "maintenanceScheduler")
    void cleanEvents() {
        while (events.deleteOlderThan(Instant.now().minusSeconds(7 * 86400L), 1000) == 1000) { }
    }

    @Scheduled(fixedDelay = 5000, scheduler = "leaseScheduler")
    void recoverExpiredLeases() {
        try {
            int recovered = leases.recoverExpired();
            if (recovered > 0) {
                log.warn("Recovered {} orphan or expired commands", recovered);
                meters.counter("buyforu_lease_recovered_total").increment(recovered);
            }
        } catch (RuntimeException failure) {
            log.error("Lease recovery failed", failure);
        }
    }

    private void dispatchLane(AgentCommand.QueueClass lane, Semaphore permits, ExecutorService executor) {
        if (!permits.tryAcquire()) return;
        UUID id;
        try { id = fairQueue.poll(lane); }
        catch (RuntimeException unavailable) { permits.release(); return; }
        if (id == null) { permits.release(); return; }
        AgentCommand command = commands.find(id).orElse(null);
        if (command == null || (command.status() != AgentCommand.CommandStatus.QUEUED
                && command.status() != AgentCommand.CommandStatus.RETRY_WAIT)
                || command.deadlineAt().isBefore(Instant.now())) {
            if (command != null) commands.markExpired(id);
            permits.release(); return;
        }
        if (!fairQueue.tryAcquireUser(command.userId(), command.commandId())) {
            fairQueue.enqueueFront(command);
            permits.release();
            return;
        }
        executor.submit(() -> execute(command, permits, true));
    }

    private void execute(AgentCommand command, Semaphore permit, boolean holdsUserPermit) {
        RunLeaseRepository.Lease lease = null;
        Timer.Sample executionSample = Timer.start(meters);
        try {
            lease = leases.claim(command, properties.instanceId(), Instant.now().plus(properties.leaseDuration()))
                    .orElse(null);
            if (lease == null) return;
            activeLeases.put(command.commandId(), lease);
            activeThreads.put(command.commandId(), Thread.currentThread());
            events.append(command.runId(), command.commandId(), "command.started",
                    Map.of("attempt", command.attempts() + 1));
            meters.counter("buyforu_command_started_total", "queue_class", command.queueClass().name()).increment();
            Timer.builder("buyforu_queue_wait_seconds").tag("queue_class", command.queueClass().name())
                    .register(meters).record(java.time.Duration.between(command.createdAt(), Instant.now()));
            ExecutionContext execution = new ExecutionContext(command.commandId(),
                    command.runId(), lease.epoch(), command.deadlineAt(), lease.stateVersion());
            ShoppingAgentState result = ExecutionContext.call(execution, () -> invoke(command));
            AgentCommand.CommandStatus terminal = result.phase() == ShoppingAgentState.Phase.CANCELLED
                    ? AgentCommand.CommandStatus.CANCELLED
                    : waiting(result) ? AgentCommand.CommandStatus.WAITING_USER : AgentCommand.CommandStatus.SUCCEEDED;
            commands.markSucceeded(command.commandId(), terminal, execution.expectedStateVersion());
            String eventType = terminal == AgentCommand.CommandStatus.WAITING_USER ? "run.waiting-user"
                    : terminal == AgentCommand.CommandStatus.CANCELLED ? "command.cancelled" : "command.completed";
            events.append(command.runId(), command.commandId(), eventType,
                    Map.of("phase", result.phase().name()));
        } catch (RunLeaseRepository.ClaimConflict conflict) {
            log.debug("Command {} was already claimed", command.commandId());
        } catch (CommandExceptions.StaleExecution stale) {
            meters.counter("buyforu_fenced_write_rejected_total").increment();
            // 栅栏拒绝意味着该 Worker 已失去写权限，不能让命令继续伪装成 RUNNING。
            commands.markFailed(command.commandId(), "STALE_EXECUTION", "任务已被更新的执行实例接管");
            events.append(command.runId(), command.commandId(), "command.failed",
                    Map.of("code", "STALE_EXECUTION"));
            log.warn("Stale command {} was fenced", command.commandId());
        } catch (DependencyExecutor.DependencyTimeoutException | CallNotPermittedException
                 | BulkheadFullException transientFailure) {
            if (command.attempts() + 1 < 3 && command.deadlineAt().isAfter(Instant.now().plusSeconds(10))) {
                commands.retryLater(command.commandId(), Instant.now().plusSeconds(10),
                        "DEPENDENCY_RETRY_WAIT", safeMessage(transientFailure));
                events.append(command.runId(), command.commandId(), "command.retry-wait",
                        Map.of("availableAt", Instant.now().plusSeconds(10).toString()));
            } else {
                commands.markFailed(command.commandId(), classify(transientFailure), safeMessage(transientFailure));
                events.append(command.runId(), command.commandId(), "command.failed",
                        Map.of("code", classify(transientFailure)));
            }
        } catch (RuntimeException failure) {
            commands.markFailed(command.commandId(), classify(failure), safeMessage(failure));
            events.append(command.runId(), command.commandId(), "command.failed",
                    Map.of("code", classify(failure)));
            log.error("Command {} failed", command.commandId(), failure);
        } finally {
            executionSample.stop(Timer.builder("buyforu_command_execution_seconds")
                    .tag("queue_class", command.queueClass().name()).register(meters));
            activeLeases.remove(command.commandId());
            activeThreads.remove(command.commandId());
            if (lease != null) leases.release(lease);
            if (holdsUserPermit) {
                try { fairQueue.releaseUser(command.userId(), command.commandId()); } catch (RuntimeException ignored) { }
            }
            permit.release();
        }
    }

    private ShoppingAgentState invoke(AgentCommand command) {
        CommandPayload payload = json.readValue(command.payload(), CommandPayload.class);
        return switch (command.commandType()) {
            case START -> workflow.start(payload.conversationId(), command.userId(), payload.message(),
                    payload.constraints(), command.idempotencyKey());
            case CLARIFY -> workflow.clarify(command.runId(), command.userId(), payload.message());
            case SELECT -> workflow.selectCandidate(command.runId(), command.userId(), payload.skuId());
            case RELAX -> workflow.relax(command.runId(), command.userId(), payload.message(),
                    payload.relaxationFields());
            case APPROVE -> workflow.approve(command.runId(), command.userId(), payload.snapshotId(), payload.summaryHash());
            case REJECT, CANCEL -> workflow.cancel(command.runId(), command.userId());
        };
    }

    private static boolean waiting(ShoppingAgentState state) {
        return switch (state.phase()) {
            case NEEDS_CLARIFICATION, PRESENTING_CANDIDATES, WAITING_APPROVAL, NEEDS_CONSTRAINT_RELAXATION -> true;
            default -> false;
        };
    }

    private static String classify(Throwable failure) {
        CommerceOperationException commerce = findCause(failure, CommerceOperationException.class);
        if (commerce != null) return commerce.code();
        String name = failure.getClass().getSimpleName();
        if (name.contains("CallNotPermitted")) return "DEPENDENCY_CIRCUIT_OPEN";
        if (name.contains("Timeout")) return "DEPENDENCY_TIMEOUT";
        if (name.contains("McpContract")) return "MCP_CONTRACT_MISMATCH";
        if (name.contains("McpInfrastructure")) return "COMMERCE_UNAVAILABLE";
        return "COMMAND_EXECUTION_FAILED";
    }

    private static String safeMessage(Throwable failure) {
        // error_detail 会被命令状态 API 返回，禁止把 MCP 内容、Prompt、URL 或认证细节原样透出。
        return switch (classify(failure)) {
            case "OUT_OF_STOCK" -> "商品库存不足，请重新选择";
            case "BUDGET_EXCEEDED", "BUDGET_BELOW_MINIMUM" -> "当前应付金额不符合已确认预算";
            case "DEPENDENCY_TIMEOUT" -> "外部服务响应超时，系统将按安全规则处理";
            case "DEPENDENCY_CIRCUIT_OPEN", "COMMERCE_UNAVAILABLE" -> "交易服务暂时不可用，请稍后重试";
            case "MCP_CONTRACT_MISMATCH" -> "Agent 与交易服务版本不兼容，请联系维护人员";
            case "STALE_EXECUTION" -> "任务已由更新的执行实例接管";
            default -> "任务执行失败，请使用错误码和 requestId 排查";
        };
    }

    private static <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return type.cast(current);
        }
        return null;
    }

    @PreDestroy
    void close() { planning.close(); transaction.close(); control.close(); }
}
