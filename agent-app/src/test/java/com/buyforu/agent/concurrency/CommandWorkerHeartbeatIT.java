package com.buyforu.agent.concurrency;

import com.buyforu.agent.it.PostgresSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 心跳续租判定在真实 PostgreSQL 上的验证。
 *
 * 单测 CommandWorkerTest 覆盖判定函数本身，覆盖不到它依赖的输入来源：
 * 判定读的是 commands.find(id).deadlineAt()，而该值必须确实是 CommandService 写进库的那一列。
 * P2 缺陷的本质是"判定依据与持久化的期限脱节"，只有端到端读到真实行才能拦住这类回归。
 *
 * 本类留在被测类同包，因为 renewLease 是包级私有测试缝，不对外暴露。
 */
@Testcontainers(disabledWithoutDocker = true)
class CommandWorkerHeartbeatIT {

    /** 必须与 CommandService.accept 中 PLANNING 通道的期限一致，否则这个测试就失去意义。 */
    private static final Duration PLANNING_DEADLINE = Duration.ofSeconds(210);
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresSupport.postgres();

    private JdbcTemplate jdbc;
    private CommandRepository commands;
    private RunLeaseRepository leases;
    private CommandWorker worker;
    private int sequence;

    @BeforeEach
    void setUp() {
        DataSource dataSource = PostgresSupport.dataSource(POSTGRES);
        jdbc = new JdbcTemplate(dataSource);
        commands = new CommandRepository(jdbc);
        leases = new RunLeaseRepository(jdbc);
        // renewLease 只用到 commands / leases / properties / inFlight，
        // 其余协作者参与的是调度与执行路径，与本判定无关，故传 null。
        worker = new CommandWorker(commands, leases, null, null, null, properties(), null, null,
                new InFlightCallRegistry());
    }

    /** 已运行 180 秒的规划命令仍须续租：这正是旧实现用 90 秒硬阈值会误杀的场景。 */
    @Test
    void renewsPlanningCommandThatRanBeyondNinetySeconds() {
        Instant acceptedAt = Instant.now();
        AgentCommand command = insertPlanningCommand(acceptedAt);
        RunLeaseRepository.Lease lease = claim(command, acceptedAt);
        Instant before = leaseUntil(command.runId());

        Instant heartbeatAt = acceptedAt.plusSeconds(180);
        assertEquals(CommandWorker.HeartbeatOutcome.RENEWED,
                worker.renewLease(command.commandId(), lease, heartbeatAt));

        Instant renewed = leaseUntil(command.runId());
        assertTrue(renewed.isAfter(before), "续租后 lease_until 必须前移");
        long skewMillis = Math.abs(Duration.between(renewed, heartbeatAt.plus(LEASE_DURATION)).toMillis());
        assertTrue(skewMillis < 1_000,
                "lease_until 应约等于心跳时刻加租约时长，实际偏差 " + skewMillis + " 毫秒");
    }

    /** 直接把库里的期限改到过去：证明判定读的是持久化列，而不是内存里的命令对象。 */
    @Test
    void stopsOnceThePersistedDeadlineHasPassed() {
        Instant acceptedAt = Instant.now();
        AgentCommand command = insertPlanningCommand(acceptedAt);
        RunLeaseRepository.Lease lease = claim(command, acceptedAt);
        Instant before = leaseUntil(command.runId());

        jdbc.update("UPDATE agent_schema.agent_command SET deadline_at=? WHERE command_id=?",
                Timestamp.from(acceptedAt.minusSeconds(1)), command.commandId());

        assertEquals(CommandWorker.HeartbeatOutcome.STOP,
                worker.renewLease(command.commandId(), lease, acceptedAt.plusSeconds(180)));
        assertEquals(before, leaseUntil(command.runId()), "停止续租后 lease_until 不应变化");
    }

    /** 边界：期限恰好等于当前时刻即视为届满，与 shouldRenewLease 的严格大于语义一致。 */
    @Test
    void stopsExactlyAtTheDeadline() {
        Instant acceptedAt = Instant.now();
        AgentCommand command = insertPlanningCommand(acceptedAt);
        RunLeaseRepository.Lease lease = claim(command, acceptedAt);

        Instant deadlineAt = acceptedAt.plus(PLANNING_DEADLINE);
        assertEquals(CommandWorker.HeartbeatOutcome.STOP,
                worker.renewLease(command.commandId(), lease, deadlineAt));
    }

    /** 用户取消优先于期限：只要 cancel_requested 为真，哪怕期限还很宽裕也必须停。 */
    @Test
    void stopsWhenCancellationWasRequested() {
        Instant acceptedAt = Instant.now();
        AgentCommand command = insertPlanningCommand(acceptedAt);
        RunLeaseRepository.Lease lease = claim(command, acceptedAt);
        Instant before = leaseUntil(command.runId());

        leases.requestCancellation(command.runId());

        assertEquals(CommandWorker.HeartbeatOutcome.STOP,
                worker.renewLease(command.commandId(), lease, acceptedAt.plusSeconds(1)));
        assertEquals(before, leaseUntil(command.runId()));
    }

    /** 租约被更高 epoch 的实例接管后，续租 SQL 命中 0 行，必须报告丢失而不是假装成功。 */
    @Test
    void reportsLeaseLostWhenAnotherWorkerTookOver() {
        Instant acceptedAt = Instant.now();
        AgentCommand command = insertPlanningCommand(acceptedAt);
        RunLeaseRepository.Lease lease = claim(command, acceptedAt);

        jdbc.update("UPDATE agent_schema.agent_run_execution SET execution_epoch=execution_epoch+1,"
                + " lease_owner='worker-b' WHERE run_id=?", command.runId());

        assertEquals(CommandWorker.HeartbeatOutcome.LEASE_LOST,
                worker.renewLease(command.commandId(), lease, acceptedAt.plusSeconds(1)));
    }

    /**
     * 租约行被 recoverExpired 清空后同样查不到行。这比"被新 epoch 接管"更容易触发：
     * 命令跑得比租约久就会命中，且 recoverExpired 每 5 秒跑一次，比跨实例竞争常见得多。
     */
    @Test
    void toleratesALeaseThatRecoveryAlreadyReclaimed() {
        Instant acceptedAt = Instant.now();
        AgentCommand command = insertPlanningCommand(acceptedAt);
        RunLeaseRepository.Lease lease = claim(command, acceptedAt);

        // 复现 recoverExpired 的效果：清空租约行归属。
        jdbc.update("UPDATE agent_schema.agent_run_execution SET active_command_id=NULL, lease_owner=NULL,"
                + " lease_until=NULL WHERE run_id=?", command.runId());

        assertEquals(CommandWorker.HeartbeatOutcome.LEASE_LOST,
                worker.renewLease(command.commandId(), lease, acceptedAt.plusSeconds(1)));
    }

    /**
     * 一条陈旧租约不得拖垮整轮心跳——这才是该缺陷真正的危害形态。
     * 若查询空结果时抛异常，异常会从 forEach 中冒出，排在它后面的命令全部错过这一轮续租。
     */
    @Test
    void staleLeaseDoesNotAbortTheRestOfTheHeartbeatCycle() {
        Instant acceptedAt = Instant.now();
        AgentCommand stale = insertPlanningCommand(acceptedAt);
        AgentCommand healthy = insertPlanningCommand(acceptedAt);
        RunLeaseRepository.Lease staleLease = claim(stale, acceptedAt);
        RunLeaseRepository.Lease healthyLease = claim(healthy, acceptedAt);
        Instant healthyBefore = leaseUntil(healthy.runId());

        jdbc.update("UPDATE agent_schema.agent_run_execution SET active_command_id=NULL, lease_owner=NULL,"
                + " lease_until=NULL WHERE run_id=?", stale.runId());

        // LinkedHashMap 固定让陈旧租约排在首位，稳定复现最坏顺序。
        Map<UUID, RunLeaseRepository.Lease> inFlightLeases = new LinkedHashMap<>();
        inFlightLeases.put(stale.commandId(), staleLease);
        inFlightLeases.put(healthy.commandId(), healthyLease);

        Instant now = acceptedAt.plusSeconds(5);
        worker.heartbeatOver(inFlightLeases, now);

        assertFalse(inFlightLeases.containsKey(stale.commandId()), "陈旧租约应被摘除");
        assertTrue(inFlightLeases.containsKey(healthy.commandId()), "健康租约不应被误删");
        Instant renewed = leaseUntil(healthy.runId());
        assertTrue(renewed.isAfter(healthyBefore), "排在陈旧租约之后的健康命令也必须在本轮完成续租");
        long skewMillis = Math.abs(Duration.between(renewed, now.plus(LEASE_DURATION)).toMillis());
        assertTrue(skewMillis < 1_000, "续租后的 lease_until 偏差 " + skewMillis + " 毫秒，超出预期");
    }

    private AgentCommand insertPlanningCommand(Instant acceptedAt) {
        int index = ++sequence;
        // 与 CommandService.accept 构造命令的方式保持一致：期限 = 受理时刻 + 通道期限。
        return commands.insert(new AgentCommand(UUID.randomUUID(), "run-hb-" + index + "-" + UUID.randomUUID(),
                "user-a", AgentCommand.CommandType.START, AgentCommand.QueueClass.PLANNING,
                "key-" + UUID.randomUUID(), "hash", "{}", AgentCommand.CommandStatus.QUEUED,
                0, acceptedAt, acceptedAt.plus(PLANNING_DEADLINE), null, null, null, null,
                acceptedAt, null, null));
    }

    private RunLeaseRepository.Lease claim(AgentCommand command, Instant at) {
        return leases.claim(command, "worker-a", at.plus(LEASE_DURATION)).orElseThrow();
    }

    private Instant leaseUntil(String runId) {
        Timestamp value = jdbc.queryForObject(
                "SELECT lease_until FROM agent_schema.agent_run_execution WHERE run_id=?",
                Timestamp.class, runId);
        return value == null ? null : value.toInstant();
    }

    private static ConcurrencyProperties properties() {
        return new ConcurrencyProperties("it-worker", LEASE_DURATION, Duration.ofSeconds(10),
                20, 8, 4,
                4, 8, 4, 4,
                1000, 1000, 20,
                30, 5, 60, 10, 120, 20, 200, 400);
    }
}
