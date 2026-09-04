package com.buyforu.agent.it;

import com.buyforu.agent.concurrency.AgentCommand;
import com.buyforu.agent.concurrency.CommandRepository;
import com.buyforu.agent.concurrency.RunLeaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多 Worker 在真实 PostgreSQL 上竞争租约。
 *
 * MultiWorkerLeaseIT 只验证两个线程一次竞争能否分出胜负，这里验证的是不变量：
 * 无论并发多高，同一个 run 都不可能同时存在两条存活租约，且接管必须递增 epoch。
 *
 * 多实例部署下"是否会重复扣款"最终依赖于这两条，因此必须用真实事务和真实行锁来证，
 * 而不是靠单测里的内存替身。
 */
@Testcontainers(disabledWithoutDocker = true)
class LeaseContentionIT {

    private static final int WORKERS = 8;
    private static final int RUNS = 12;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresSupport.postgres();

    private JdbcTemplate jdbc;
    private CommandRepository commands;
    private RunLeaseRepository leases;
    private TransactionTemplate transactions;
    private int sequence;

    @BeforeEach
    void setUp() {
        DataSource dataSource = PostgresSupport.dataSource(POSTGRES, WORKERS + 4);
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        commands = new CommandRepository(jdbc);
        leases = new RunLeaseRepository(jdbc);
    }

    /** 12 个 run × 8 个 worker 同时抢，每个 run 有且只有一个赢家。 */
    @Test
    void onlyOneWorkerWinsPerRunUnderContention() throws Exception {
        List<AgentCommand> pending = new ArrayList<>();
        for (int i = 0; i < RUNS; i++) pending.add(insert(newRunId()));

        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        try {
            // 用栅栏对齐起跑，否则线程逐个启动，竞争是假的。
            CountDownLatch start = new CountDownLatch(1);
            List<Callable<RunLeaseRepository.Lease>> tasks = new ArrayList<>();
            for (AgentCommand command : pending) {
                for (int w = 0; w < WORKERS; w++) {
                    String owner = "worker-" + w;
                    tasks.add(() -> {
                        start.await();
                        // claim 在 Spring 容器里靠 @Transactional 保证原子性。这里没有代理，
                        // 必须显式包事务，否则 SELECT ... FOR UPDATE 不跨语句持锁，测不出真实语义。
                        return transactions.execute(status ->
                                leases.claim(command, owner, Instant.now().plusSeconds(30)).orElse(null));
                    });
                }
            }
            List<Future<RunLeaseRepository.Lease>> futures = new ArrayList<>();
            for (Callable<RunLeaseRepository.Lease> task : tasks) futures.add(pool.submit(task));
            start.countDown();

            int winners = 0;
            for (Future<RunLeaseRepository.Lease> future : futures) {
                if (future.get(30, TimeUnit.SECONDS) != null) winners++;
            }
            assertEquals(RUNS, winners, "每个 run 只能有一个 worker 领到租约");
        } finally {
            pool.shutdownNow();
        }

        // 回到数据库核验不变量，不依赖 claim 的返回值自证。
        assertEquals(0, countRunsWithDuplicateLiveLeases(), "不允许同一 run 出现两条存活租约");
    }

    /** 租约过期后由同一条命令重试接管，旧 epoch 必须被栅栏挡住，不能继续续租。 */
    @Test
    void takeoverFencesThePreviousEpoch() {
        String runId = newRunId();
        AgentCommand command = insert(runId);
        RunLeaseRepository.Lease firstLease = transactions.execute(status ->
                leases.claim(command, "worker-a", Instant.now().plusSeconds(30)).orElseThrow());
        assertTrue(leases.isCurrent(runId, command.commandId(), firstLease.epoch()));

        // 直接把租约改到过去以复现"命令跑得比租约久"。比 sleep 确定，也快得多。
        jdbc.update("UPDATE agent_schema.agent_run_execution SET lease_until=? WHERE run_id=?",
                Timestamp.from(Instant.now().minusSeconds(1)), runId);

        // 生产中的重试由 recoverExpired 把命令放回 RETRY_WAIT 后再次 claim 同一条命令；
        // claim 明确拒绝用另一条命令抢占同一 run（见 refusesToTakeOverWithADifferentCommand），
        // 因此这里必须复用同一条，否则测的根本不是生产路径。
        RunLeaseRepository.Lease secondLease = transactions.execute(status ->
                leases.claim(command, "worker-b", Instant.now().plusSeconds(30)).orElse(null));

        assertNotNull(secondLease, "租约过期后同一条命令必须能被重新领取");
        assertEquals(firstLease.epoch() + 1, secondLease.epoch(), "接管必须递增 epoch");

        // 旧 epoch 已被栅栏挡住：续租落空、isCurrent 为假；新 epoch 仍然有效。
        assertFalse(leases.heartbeat(firstLease, Instant.now().plusSeconds(30)), "旧 epoch 的续租必须失败");
        assertFalse(leases.isCurrent(runId, command.commandId(), firstLease.epoch()));
        assertTrue(leases.isCurrent(runId, command.commandId(), secondLease.epoch()));
    }

    /**
     * 过期恢复后不得用另一条命令抢占同一 run。这是刻意设计而非缺陷：
     * 若允许抢占，后到的命令会越过 run 内顺序。正确做法是先让旧命令重回公平队列。
     */
    @Test
    void refusesToTakeOverWithADifferentCommand() {
        String runId = newRunId();
        AgentCommand first = insert(runId);
        transactions.execute(status ->
                leases.claim(first, "worker-a", Instant.now().plusSeconds(30)).orElseThrow());

        jdbc.update("UPDATE agent_schema.agent_run_execution SET lease_until=? WHERE run_id=?",
                Timestamp.from(Instant.now().minusSeconds(1)), runId);

        AgentCommand second = insert(runId);
        RunLeaseRepository.Lease takeover = transactions.execute(status ->
                leases.claim(second, "worker-b", Instant.now().plusSeconds(30)).orElse(null));

        assertNull(takeover, "不得用另一条命令抢占同一 run，否则会越过 run 内顺序");
        assertEquals("RETRY_WAIT", statusOf(first.commandId()), "旧命令应被恢复为可重试");
    }

    private String newRunId() {
        return "run-contend-" + (++sequence) + "-" + UUID.randomUUID();
    }

    private AgentCommand insert(String runId) {
        Instant now = Instant.now();
        return commands.insert(new AgentCommand(UUID.randomUUID(), runId, "user-a",
                AgentCommand.CommandType.START, AgentCommand.QueueClass.PLANNING,
                "key-" + UUID.randomUUID(), "hash", "{}", AgentCommand.CommandStatus.QUEUED,
                0, now, now.plusSeconds(60), null, null, null, null, now, null, null));
    }

    private String statusOf(UUID commandId) {
        return jdbc.queryForObject(
                "SELECT status FROM agent_schema.agent_command WHERE command_id=?", String.class, commandId);
    }

    private int countRunsWithDuplicateLiveLeases() {
        // count(*) 恒返回一行，不存在空结果隐患（见评审文档不变量 I7）。
        Integer duplicates = jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT run_id FROM agent_schema.agent_run_execution
                    WHERE active_command_id IS NOT NULL AND lease_until > now()
                    GROUP BY run_id HAVING count(*) > 1
                ) duplicated
                """, Integer.class);
        return duplicates == null ? 0 : duplicates;
    }
}
