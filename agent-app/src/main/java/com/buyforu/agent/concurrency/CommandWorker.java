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
        Instant now = Instant.now();
        activeLeases.forEach((commandId, lease) -> {
            boolean cancelRequested = leases.cancellationRequested(lease);
            // 判据是命令期限而非已运行时长：PLANNING 允许 210 秒，慢模型响应与三级 Replan 必须能跑满该期限。
            Instant deadlineAt = commands.find(commandId).map(AgentCommand::deadlineAt).orElse(null);
            if (cancelRequested || !shouldRenewLease(deadlineAt, now)) {
                inFlight.cancel(commandId);
                Thread worker = activeThreads.get(commandId);
                if (worker != null) worker.interrupt();
                // 取消或期限届满后不能再续租，否则 recoverExpired 永远看不到这条 RUNNING。
                return;
            }
            if (!leases.heartbeat(lease, now.plus(properties.leaseDuration()))) {
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
                // 租约恢复后立刻丢掉该命令持有的用户许可，避免再堵满 240 秒。
                for (AgentCommand command : commands.recentlyRecovered(15)) {
                    try { fairQueue.releaseUser(command.userId(), command.commandId()); }
                    catch (RuntimeException ignored) { }
                }
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
        } catch (DependencyExecutor.DependencyInterruptedException interrupted) {
            // 必须排在 catch (RuntimeException) 之前，否则这个子类永远落不进来。
            // heartbeat 在"用户取消"和"期限届满"两种情况下都会 interrupt，此处把两者分开，
            // 否则都落成通用的 COMMAND_EXECUTION_FAILED，运维无法识别命令是被终止还是真的出错。
            if (lease != null && leases.cancellationRequested(lease)) {
                commands.markCancelled(command.commandId(), "RUN_CANCEL_REQUESTED");
                events.append(command.runId(), command.commandId(), "command.cancelled",
                        Map.of("code", "RUN_CANCEL_REQUESTED"));
            } else {
                commands.markFailed(command.commandId(), "COMMAND_DEADLINE_EXCEEDED", safeMessage(interrupted));
                meters.counter("buyforu_command_deadline_terminated_total",
                        "queue_class", command.queueClass().name()).increment();
                events.append(command.runId(), command.commandId(), "command.failed",
                        Map.of("code", "COMMAND_DEADLINE_EXCEEDED"));
                log.warn("Command {} exceeded its deadline and was terminated", command.commandId());
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

    /**
     * 续租的唯一判据是命令是否仍在期限内，而不是已经运行了多久。
     * 已运行时长不构成终止理由：PLANNING 命令期限为 210 秒，慢模型响应与三级 Replan 必须能跑满该期限。
     * 历史上这里用 startedAt 加 90 秒硬阈值，会把所有超过 90 秒的合法规划任务判死。
     */
    static boolean shouldRenewLease(Instant deadlineAt, Instant now) {
        return deadlineAt == null || deadlineAt.isAfter(now);
    }

    static String classify(Throwable failure) {
        CommerceOperationException commerce = findCause(failure, CommerceOperationException.class);
        if (commerce != null) return commerce.code();
        if (causeNameContains(failure, "McpContract")) return "MCP_CONTRACT_MISMATCH";
        if (causeNameContains(failure, "McpInfrastructure")) return "COMMERCE_UNAVAILABLE";
        if (causeNameContains(failure, "CallNotPermitted")) return "DEPENDENCY_CIRCUIT_OPEN";
        // 中断判定必须早于 Timeout：DependencyInterruptedException 语义是"被终止"，
        // 若先判 Timeout 会把期限届满终止误报成外部服务超时。
        if (causeNameContains(failure, "DependencyInterrupted")) return "COMMAND_DEADLINE_EXCEEDED";
        if (causeNameContains(failure, "Timeout")) return "DEPENDENCY_TIMEOUT";
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
            case "COMMAND_DEADLINE_EXCEEDED" -> "任务处理超时，请稍后重试";
            default -> "任务执行失败，请使用错误码和 requestId 排查";
        };
    }

    private static boolean causeNameContains(Throwable failure, String token) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getClass().getSimpleName().contains(token)) return true;
        }
        return false;
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
