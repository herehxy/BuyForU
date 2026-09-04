package com.buyforu.agent.it;

import com.buyforu.agent.concurrency.AgentCommand;
import com.buyforu.agent.concurrency.CommandRepository;
import com.buyforu.agent.concurrency.ExecutionContext;
import com.buyforu.agent.concurrency.RunLeaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 两个 Worker 同时 claim 同一个 run，只能有一个拿到租约。 */
@Testcontainers(disabledWithoutDocker = true)
class MultiWorkerLeaseIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresSupport.postgres();

    private CommandRepository commands;
    private RunLeaseRepository first;
    private RunLeaseRepository second;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        var dataSource = PostgresSupport.dataSource(POSTGRES);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        commands = new CommandRepository(jdbc);
        first = new RunLeaseRepository(jdbc);
        second = new RunLeaseRepository(jdbc);
    }

    @Test
    void onlyOneWorkerClaimsARun() throws Exception {
        AgentCommand command = insert("run-lease", "user-a");
        var pool = Executors.newFixedThreadPool(2);
        try {
            Instant until = Instant.now().plusSeconds(30);
            List<Callable<Boolean>> tasks = List.of(
                    () -> Boolean.TRUE.equals(transactions.execute(status ->
                            first.claim(command, "worker-a", until).isPresent())),
                    () -> Boolean.TRUE.equals(transactions.execute(status ->
                            second.claim(command, "worker-b", until).isPresent())));
            long winners = pool.invokeAll(tasks).stream().filter(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    return false;
                }
            }).count();
            assertEquals(1, winners);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void expiredLeaseCanBeReclaimedWithNewEpoch() {
        AgentCommand command = insert("run-expire", "user-a");
        Optional<RunLeaseRepository.Lease> firstLease = transactions.execute(status ->
                first.claim(command, "worker-a", Instant.now().minusSeconds(5)));
        assertEquals(true, firstLease != null && firstLease.isPresent());
        transactions.executeWithoutResult(status -> first.recoverExpired());
        Optional<RunLeaseRepository.Lease> secondLease = transactions.execute(status ->
                second.claim(command, "worker-b", Instant.now().plusSeconds(30)));
        assertEquals(true, secondLease != null && secondLease.isPresent());
        assertEquals(firstLease.orElseThrow().epoch() + 1, secondLease.orElseThrow().epoch());
    }

    @Test
    void currentControlCommandLeaseIsNotAConflictForItself() {
        AgentCommand cancel = insert("run-cancel", "user-a", AgentCommand.CommandType.CANCEL,
                AgentCommand.QueueClass.CONTROL);
        RunLeaseRepository.Lease lease = transactions.execute(status ->
                first.claim(cancel, "worker-control", Instant.now().plusSeconds(30)).orElseThrow());

        // 在 Worker 上下文外，这仍是一条活租约。
        assertTrue(first.hasConflictingLiveLease(cancel.runId()));
        ExecutionContext context = new ExecutionContext(cancel.commandId(), cancel.runId(), lease.epoch(),
                cancel.deadlineAt(), lease.stateVersion());
        // 进入该 CONTROL Worker 后，它不能把自己当成下单 Worker。
        assertFalse(ExecutionContext.call(context, () -> first.hasConflictingLiveLease(cancel.runId())));
    }

    private AgentCommand insert(String runId, String userId) {
        return insert(runId, userId, AgentCommand.CommandType.START, AgentCommand.QueueClass.PLANNING);
    }

    private AgentCommand insert(String runId, String userId, AgentCommand.CommandType type,
                                AgentCommand.QueueClass queueClass) {
        Instant now = Instant.now();
        AgentCommand command = new AgentCommand(UUID.randomUUID(), runId, userId,
                type, queueClass,
                "key-" + UUID.randomUUID(), "hash", "{}", AgentCommand.CommandStatus.QUEUED,
                0, now, now.plusSeconds(60), null, null, null, null, now, null, null);
        return commands.insert(command);
    }
}
