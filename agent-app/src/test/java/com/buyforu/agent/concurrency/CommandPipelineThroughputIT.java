package com.buyforu.agent.concurrency;

import com.buyforu.agent.application.GraphShoppingWorkflow;
import com.buyforu.agent.domain.ShoppingAgentState;
import com.buyforu.agent.it.PostgresSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 并发治理内核的吞吐与公平性验证：真实的 Postgres + Redis，真实的
 * accept → 令牌桶限流 → 公平队列入队 → 出队 → 租约领取 → 执行 → 许可释放全链路。
 *
 * 唯一被桩掉的是业务图：Mockito 替身让 {@code workflow.start} 立即返回完成态。
 * 这样不依赖 LLM 和 Keycloak 就能量化"治理层每秒能推进多少命令、排队延迟分布如何"，
 * 而这些数字恰是"高并发治理"这条设计主张的实证。HTTP 认证与 SSE 不在本测试范围。
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommandPipelineThroughputIT {

    private static final int USERS = 20;
    private static final int COMMANDS_PER_USER = 10;
    private static final int TOTAL = USERS * COMMANDS_PER_USER;
    private static final int ACCEPT_THREADS = 16;
    private static final int DISPATCH_THREADS = 8;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresSupport.postgres();

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    private AnnotationConfigApplicationContext context;
    private CommandService commandService;
    private CommandWorker worker;
    private JdbcTemplate jdbc;
    private static final AtomicInteger startedByWorkflow = new AtomicInteger();
    private final ConcurrentHashMap<String, Instant> acceptedAt = new ConcurrentHashMap<>();

    /** 高容量配置：让限流与队列容量不成为瓶颈，单独度量治理内核本身的吞吐。 */
    static ConcurrencyProperties throughputProperties() {
        return new ConcurrencyProperties("throughput-it", Duration.ofSeconds(30), Duration.ofSeconds(10),
                16, 8, 4,
                16, 16, 8, 8,
                5000, 5000, 100,
                100000, 1000, 100000, 1000, 100000, 1000, 100000, 1000);
    }

    @BeforeAll
    void startContext() {
        context = new AnnotationConfigApplicationContext(PipelineConfig.class);
        commandService = context.getBean(CommandService.class);
        worker = context.getBean(CommandWorker.class);
        jdbc = context.getBean(JdbcTemplate.class);
    }

    @AfterAll
    void stopContext() {
        if (context != null) context.close();
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class PipelineConfig {

        @Bean(destroyMethod = "close")
        javax.sql.DataSource dataSource() { return PostgresSupport.dataSource(POSTGRES, DISPATCH_THREADS + 8); }

        @Bean
        JdbcTemplate jdbcTemplate(javax.sql.DataSource dataSource) { return new JdbcTemplate(dataSource); }

        @Bean
        DataSourceTransactionManager transactionManager(javax.sql.DataSource dataSource) {
            // claim 标注 @Transactional；离开 Spring 代理它不会生效，
            // SELECT ... FOR UPDATE 也就无法跨语句持锁，公平队列与租约互斥都会失真。
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        CommandRepository commandRepository(JdbcTemplate jdbc) { return new CommandRepository(jdbc); }

        @Bean
        RunLeaseRepository runLeaseRepository(JdbcTemplate jdbc) { return new RunLeaseRepository(jdbc); }

        @Bean
        RunEventNotifier runEventNotifier(StringRedisTemplate redis) { return new RunEventNotifier(redis); }

        @Bean
        RunEventRepository runEventRepository(JdbcTemplate jdbc, ObjectMapper json, RunEventNotifier notifier) {
            return new RunEventRepository(jdbc, json, notifier);
        }

        @Bean(destroyMethod = "destroy")
        LettuceConnectionFactory lettuceConnectionFactory() {
            LettuceConnectionFactory factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
            factory.afterPropertiesSet();
            return factory;
        }

        @Bean
        StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory factory) {
            StringRedisTemplate template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();
            return template;
        }

        @Bean
        ConcurrencyProperties concurrencyProperties() { return throughputProperties(); }

        @Bean
        io.micrometer.core.instrument.MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }

        @Bean
        RedisFairQueue redisFairQueue(StringRedisTemplate redis, ConcurrencyProperties properties,
                                      io.micrometer.core.instrument.MeterRegistry meters) {
            return new RedisFairQueue(redis, properties, meters);
        }

        @Bean
        RedisAdmissionController redisAdmissionController(StringRedisTemplate redis, ConcurrencyProperties properties,
                                                          io.micrometer.core.instrument.MeterRegistry meters) {
            return new RedisAdmissionController(redis, properties, meters);
        }

        @Bean
        ObjectMapper objectMapper() { return new ObjectMapper(); }

        @Bean
        InFlightCallRegistry inFlightCallRegistry() { return new InFlightCallRegistry(); }

        /** 业务图替身：立即返回完成态，使整条治理链路不依赖 LLM 即可驱动。 */
        @Bean
        GraphShoppingWorkflow graphShoppingWorkflow() {
            GraphShoppingWorkflow workflow = mock(GraphShoppingWorkflow.class);
            when(workflow.start(anyString(), anyString(), anyString(), any(), anyString()))
                    .thenAnswer(invocation -> {
                        // 驱动循环以该计数器判断队列是否排空，桩必须如实记账。
                        startedByWorkflow.incrementAndGet();
                        return completedState(invocation.getArgument(0, String.class));
                    });
            return workflow;
        }

        @Bean
        CommandService commandService(CommandRepository commands, RedisAdmissionController admission,
                                      RedisFairQueue fairQueue, RunEventRepository events,
                                      RunLeaseRepository leases, ObjectMapper json) {
            return new CommandService(commands, admission, fairQueue, events, leases, json);
        }

        @Bean
        CommandWorker commandWorker(CommandRepository commands, RunLeaseRepository leases,
                                    RedisFairQueue fairQueue, GraphShoppingWorkflow workflow,
                                    RunEventRepository events, ConcurrencyProperties properties,
                                    ObjectMapper json, io.micrometer.core.instrument.MeterRegistry meters,
                                    InFlightCallRegistry inFlight) {
            return new CommandWorker(commands, leases, fairQueue, workflow, events, properties, json, meters, inFlight);
        }
    }

    private static ShoppingAgentState completedState(String runId) {
        return new ShoppingAgentState(runId, null, null, null, null, null,
                ShoppingAgentState.Phase.COMPLETED, null, 0, null, null, null, 0, 0, 0, null, null, Instant.now());
    }

    /**
     * 预热：熔断器以 80ms 慢调用阈值统计，冷启动的 Lettuce 连接在 16 线程并发下首批评估
     * Lua 脚本会超过该阈值，直接把 'redis-coordination' 打穿——这是测试冷启动的伪影，
     * 不是治理内核缺陷（生产环境连接池常温）。先建立连接，再串行走 20 次真实 admit
     * 把熔断器滑动窗口填满成功记录；这些调用不产生命令数据。
     */
    private void warmUpRedis() {
        StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);
        for (int i = 0; i < 5; i++) {
            redis.opsForValue().set("{buyforu}:it-warmup", String.valueOf(i));
        }
        RedisAdmissionController admission = context.getBean(RedisAdmissionController.class);
        for (int i = 0; i < 20; i++) {
            admission.admit("it-warmup-" + i, "10.255.255.254", AgentCommand.QueueClass.PLANNING);
        }
    }

    @Test
    void drainsTwoHundredCommandsThroughTheGovernanceCore() throws Exception {
        warmUpRedis();
        ExecutorService acceptPool = Executors.newFixedThreadPool(ACCEPT_THREADS);
        ExecutorService dispatchPool = Executors.newFixedThreadPool(DISPATCH_THREADS);
        try {
            // 阶段一：200 条命令并发受理，只进队、不消费。公平队列的行为因此与受理顺序无关。
            List<UUID> commandIds = acceptAll(acceptPool);
            assertEquals(TOTAL, commandIds.size());

            // 阶段二：多个驱动线程模拟调度器的 fixedDelay，直到队列排空。
            long begin = System.nanoTime();
            CountDownLatch drained = new CountDownLatch(1);
            List<Future<?>> drivers = new ArrayList<>();
            for (int i = 0; i < DISPATCH_THREADS; i++) {
                drivers.add(dispatchPool.submit(() -> {
                    while (startedByWorkflow.get() < TOTAL) {
                        worker.dispatch();
                        LockSupport.parkNanos(200_000);
                    }
                    drained.countDown();
                    return null;
                }));
            }
            for (Future<?> driver : drivers) driver.get(60, java.util.concurrent.TimeUnit.SECONDS);

            // 等待最后一批命令落成终态（执行提交与状态落库之间有微小窗口）。
            waitUntilAllTerminal(commandIds);
            long elapsedNanos = System.nanoTime() - begin;

            assertInvariants(commandIds);
            reportMetrics(elapsedNanos);
        } finally {
            acceptPool.shutdownNow();
            dispatchPool.shutdownNow();
        }
    }

    private List<UUID> acceptAll(ExecutorService pool) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<UUID>> futures = new ArrayList<>();
        for (int u = 0; u < USERS; u++) {
            String userId = "load-user-" + u;
            // 每用户独立模拟 IP：生产代码的 IP 写桶硬编码 burst=30，
            // 全部 200 条命令若来自同一 IP，第 31 条起必然被正确地拒绝。
            String remoteAddress = "10.0.0." + u;
            for (int c = 0; c < COMMANDS_PER_USER; c++) {
                String idempotencyKey = "idem-" + u + "-" + c;
                futures.add(pool.submit(() -> {
                    start.await();
                    // START 的 runId 与 (userId, idempotencyKey) 绑定：同键重试不会产生第二个任务。
                    String runId = UUID.nameUUIDFromBytes(
                            ("buyforu-run\u001f" + userId + "\u001f" + idempotencyKey)
                                    .getBytes(StandardCharsets.UTF_8)).toString();
                    CommandAccepted accepted = commandService.accept(runId, userId, remoteAddress, idempotencyKey,
                            AgentCommand.CommandType.START, AgentCommand.QueueClass.PLANNING,
                            new CommandPayload("conv-" + idempotencyKey, "5000 元以内的笔记本",
                                    null, null, null, null, null));
                    acceptedAt.put(accepted.commandId().toString(), Instant.now());
                    return accepted.commandId();
                }));
            }
        }
        start.countDown();
        List<UUID> ids = new ArrayList<>(futures.size());
        for (Future<UUID> future : futures) ids.add(future.get(60, java.util.concurrent.TimeUnit.SECONDS));
        return ids;
    }

    private void waitUntilAllTerminal(List<UUID> commandIds) throws InterruptedException {
        String inList = String.join(",", commandIds.stream().map(id -> "'" + id + "'").toList());
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Integer remaining = jdbc.queryForObject("""
                    SELECT count(*) FROM agent_schema.agent_command
                    WHERE command_id IN (%s) AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED')
                    """.formatted(inList), Integer.class);
            if (remaining != null && remaining == 0) return;
            Thread.sleep(50);
        }
        throw new AssertionError("30 秒内未全部到达终态");
    }

    private void assertInvariants(List<UUID> commandIds) {
        String inList = String.join(",", commandIds.stream().map(id -> "'" + id + "'").toList());

        // 1) 全部成功，没有命令停留在中间态。
        Integer succeeded = jdbc.queryForObject(
                "SELECT count(*) FROM agent_schema.agent_command WHERE command_id IN (%s) AND status='SUCCEEDED'"
                        .formatted(inList), Integer.class);
        assertEquals(TOTAL, succeeded, "所有命令都应成功");

        // 2) 没有残留的存活租约（execute 的 finally 必须释放每一笔）。
        Integer liveLeases = jdbc.queryForObject("""
                SELECT count(*) FROM agent_schema.agent_run_execution
                WHERE active_command_id IN (%s) AND lease_until > now()
                """.formatted(inList), Integer.class);
        assertEquals(0, liveLeases, "不应残留存活租约");

        // 3) 公平队列回到空态。depth 与 virtual-time 是常驻度量键（排空后留在 0，不删除），
        //    真正代表脏状态的是用户队列、活跃用户 zset、索引 set 与执行许可，排空后必须全部消失。
        StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);
        String depthValue = redis.opsForValue().get("{buyforu}:queue:planning:depth");
        assertTrue(depthValue == null || Long.parseLong(depthValue) == 0,
                "规划队列深度应为 0，实际=" + depthValue);
        assertEquals(0, redisKeyCount("{buyforu}:queue:planning:user:*"), "用户队列应排空");
        assertEquals(0, redisKeyCount("{buyforu}:queue:planning:active-users"), "活跃用户 zset 应清空");
        assertEquals(0, redisKeyCount("{buyforu}:queue:planning:indexed"), "命令索引 set 应清空");
        assertEquals(0, redisKeyCount("{buyforu}:running:user:*"), "用户执行许可应全部释放");

        // 4) 公平性：任何用户的最后一条命令开始前，每个用户的第一条命令都已开始。
        //    严格轮转下不存在"一个用户被清空而另一个还没轮到"的饿死形态。
        Instant firstRoundMax = jdbc.queryForObject("""
                SELECT max(first_start) FROM (
                    SELECT min(started_at) AS first_start FROM agent_schema.agent_command
                    WHERE command_id IN (%s) GROUP BY user_id
                ) rounds
                """.formatted(inList), Timestamp.class).toInstant();
        Instant lastRoundMin = jdbc.queryForObject("""
                SELECT min(last_start) FROM (
                    SELECT max(started_at) AS last_start FROM agent_schema.agent_command
                    WHERE command_id IN (%s) GROUP BY user_id
                ) rounds
                """.formatted(inList), Timestamp.class).toInstant();
        assertTrue(!firstRoundMax.isAfter(lastRoundMin),
                "出现用户饿死：firstRoundMax=" + firstRoundMax + " lastRoundMin=" + lastRoundMin);
    }

    private long redisKeyCount(String pattern) {
        StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);
        Long count = redis.execute((org.springframework.data.redis.connection.RedisConnection connection) -> {
            long total = 0;
            try (var cursor = connection.keyCommands().scan(
                    org.springframework.data.redis.core.ScanOptions.scanOptions().match(pattern).count(1000).build())) {
                while (cursor.hasNext()) { cursor.next(); total++; }
            }
            return total;
        });
        return count == null ? -1 : count;
    }

    private void reportMetrics(long elapsedNanos) {
        record Row(long waitMs, long e2eMs) { }
        List<Row> rows = jdbc.query("""
                SELECT EXTRACT(EPOCH FROM (started_at - created_at)) * 1000 AS wait_ms,
                       EXTRACT(EPOCH FROM (completed_at - created_at)) * 1000 AS e2e_ms
                FROM agent_schema.agent_command WHERE status='SUCCEEDED' AND created_at > now() - interval '10 minutes'
                """, (rs, i) -> new Row(rs.getLong(1), rs.getLong(2)));

        double seconds = elapsedNanos / 1_000_000_000.0;
        List<Long> e2e = rows.stream().map(Row::e2eMs).sorted().toList();
        List<Long> wait = rows.stream().map(Row::waitMs).sorted().toList();
        System.out.printf("""
                ============ 并发治理内核吞吐报告（%d 条命令，%d 用户）============
                总吞吐      : %.0f 条/秒（含受理、排队、执行、释放全链路，业务图为替身）
                端到端延迟  : p50=%d ms  p95=%d ms  p99=%d ms  max=%d ms
                排队等待    : p50=%d ms  p95=%d ms  p99=%d ms  max=%d ms
                ================================================================
                """, TOTAL, USERS, TOTAL / seconds,
                percentile(e2e, 50), percentile(e2e, 95), percentile(e2e, 99), e2e.getLast(),
                percentile(wait, 50), percentile(wait, 95), percentile(wait, 99), wait.getLast());

        // 回归金丝雀：治理内核是微秒级操作，低于该值说明出现了病态退化
        // （例如 Lua 脚本退化为逐键往返、或租约事务退化为全表锁）。不设上限，避免对硬件敏感。
        assertTrue(TOTAL / seconds >= 10, "吞吐 " + TOTAL / seconds + " 条/秒低于回归金丝雀下限 10 条/秒");
    }

    private static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return -1;
        int index = Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * p / 100.0) - 1);
        return sorted.get(Math.max(0, index));
    }
}
