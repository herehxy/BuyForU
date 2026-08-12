package com.buyforu.agent.api;

import com.buyforu.agent.concurrency.AgentCommand;
import com.buyforu.agent.concurrency.CommandService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.time.Instant;

/** 查询持久化命令状态；响应只允许命令所属用户读取。 */
@RestController
@RequestMapping("/api/v1/commands")
public class CommandController {
    private final CommandService commands;
    public CommandController(CommandService commands) { this.commands = commands; }

    @GetMapping("/{commandId}")
    CommandView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID commandId) {
        AgentCommand command = commands.get(commandId, AuthenticatedUser.id(jwt));
        return new CommandView(command.commandId(), command.runId(), command.status(), command.queueClass(),
                command.attempts(), command.availableAt(), command.deadlineAt(), command.resultStateVersion(),
                command.errorCode(), command.errorDetail(), command.createdAt(), command.startedAt(),
                command.completedAt());
    }

    /** 不返回 payload、请求哈希和幂等键，避免在状态查询中复制用户输入。 */
    public record CommandView(UUID commandId, String runId, AgentCommand.CommandStatus status,
                              AgentCommand.QueueClass queueClass, int attempts, Instant availableAt,
                              Instant deadlineAt, Long resultStateVersion, String errorCode, String errorDetail,
                              Instant createdAt, Instant startedAt, Instant completedAt) { }
}
