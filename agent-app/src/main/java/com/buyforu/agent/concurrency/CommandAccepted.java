package com.buyforu.agent.concurrency;

import java.time.Instant;
import java.util.UUID;

/** 写接口的轻量响应；任务的最终结果由 SSE 或状态查询取得。 */
public record CommandAccepted(UUID commandId, String runId, AgentCommand.CommandStatus status,
                              AgentCommand.QueueClass queueClass, Instant acceptedAt, Instant deadlineAt,
                              String eventUrl, String statusUrl) {
    static CommandAccepted from(AgentCommand command) {
        return new CommandAccepted(command.commandId(), command.runId(), command.status(), command.queueClass(),
                command.createdAt(), command.deadlineAt(), "/api/v1/runs/" + command.runId() + "/events",
                "/api/v1/commands/" + command.commandId());
    }
}
