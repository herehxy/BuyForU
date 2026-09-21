package com.buyforu.agent.it;

import com.buyforu.agent.concurrency.AgentCommand;
import com.buyforu.agent.concurrency.CommandRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 主人只认 START / agent_run，事后插入的 CANCEL 不能让攻击者变成主人。 */
@Testcontainers(disabledWithoutDocker = true)
class RunOwnershipIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresSupport.postgres();

    private CommandRepository commands;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(PostgresSupport.dataSource(POSTGRES));
        commands = new CommandRepository(jdbc);
    }

    @Test
    void foreignCancelDoesNotTransferOwnership() {
        commands.insert(command("run-a", "user-a", AgentCommand.CommandType.START, "start-a"));
        assertTrue(commands.ownsRun("run-a", "user-a"));
        assertFalse(commands.ownsRun("run-a", "user-b"));

        commands.insert(command("run-a", "user-b", AgentCommand.CommandType.CANCEL, "cancel-b"));

        assertEquals("user-a", commands.runOwner("run-a").orElseThrow());
        assertFalse(commands.ownsRun("run-a", "user-b"));
    }

    private static AgentCommand command(String runId, String userId, AgentCommand.CommandType type, String key) {
        Instant now = Instant.parse("2026-08-12T08:00:00Z");
        return new AgentCommand(UUID.randomUUID(), runId, userId, type,
                type == AgentCommand.CommandType.CANCEL
                        ? AgentCommand.QueueClass.CONTROL : AgentCommand.QueueClass.PLANNING,
                key, "hash", "{}", AgentCommand.CommandStatus.QUEUED, 0, now, now.plusSeconds(30),
                null, null, null, null, now, null, null);
    }
}
