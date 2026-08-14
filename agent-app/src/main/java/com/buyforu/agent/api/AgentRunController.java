package com.buyforu.agent.api;

import com.buyforu.agent.application.GraphShoppingWorkflow;
import com.buyforu.agent.domain.PlanSpec.ShoppingConstraints;
import com.buyforu.agent.domain.ShoppingAgentState;
import com.buyforu.agent.concurrency.AgentCommand;
import com.buyforu.agent.concurrency.CommandAccepted;
import com.buyforu.agent.concurrency.CommandPayload;
import com.buyforu.agent.concurrency.CommandService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import com.buyforu.commerce.port.model.CommerceModels.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 购物任务 HTTP API。userId 只从已校验 JWT 的 sub 获取，绝不接受客户端自报身份。
 * Controller 负责协议校验，实际状态转换交给 GraphShoppingWorkflow。
 */
@RestController
@RequestMapping("/api/v1")
public class AgentRunController {
    private final GraphShoppingWorkflow workflow;
    private final CommandService commands;
    private final ClientAddressResolver clientAddresses;

    public AgentRunController(GraphShoppingWorkflow workflow, CommandService commands,
                              ClientAddressResolver clientAddresses) {
        this.workflow = workflow;
        this.commands = commands;
        this.clientAddresses = clientAddresses;
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    CommandAccepted start(@AuthenticationPrincipal Jwt jwt,
                          HttpServletRequest http,
                          @RequestHeader("Idempotency-Key") String idempotencyKey,
                          @Valid @RequestBody StartRunRequest request) {
        ConstraintInput supplied = request.constraints();
        ShoppingConstraints secured = new ShoppingConstraints(
                supplied == null ? null : supplied.query(), supplied == null ? null : supplied.category(),
                supplied == null ? null : supplied.budgetMax(),
                supplied == null ? null : supplied.budgetMin(),
                supplied == null ? null : supplied.preferredBrands(),
                supplied == null ? null : supplied.excludedBrands(),
                supplied == null ? null : supplied.requiredAttributes(),
                supplied == null ? 0 : supplied.quantity(), request.addressId(),
                supplied == null ? null : supplied.deliveryBy(), 1);
        String userId = AuthenticatedUser.id(jwt);
        String runId = UUID.nameUUIDFromBytes(("buyforu-run\u001f" + userId + "\u001f" + idempotencyKey)
                .getBytes(StandardCharsets.UTF_8)).toString();
        return commands.accept(runId, userId, clientAddresses.resolve(http), idempotencyKey, AgentCommand.CommandType.START,
                AgentCommand.QueueClass.PLANNING,
                new CommandPayload(request.conversationId(), request.message(), null, null, null, secured, null));
    }

    @GetMapping("/runs/{runId}")
    ShoppingAgentState get(@AuthenticationPrincipal Jwt jwt, @PathVariable String runId) {
        return workflow.get(runId, AuthenticatedUser.id(jwt));
    }

    @GetMapping("/runs")
    java.util.List<ShoppingAgentState> recent(@AuthenticationPrincipal Jwt jwt) {
        return workflow.recent(AuthenticatedUser.id(jwt), 20);
    }

    @PostMapping("/runs/{runId}/selection")
    @ResponseStatus(HttpStatus.ACCEPTED)
    CommandAccepted select(@AuthenticationPrincipal Jwt jwt, @PathVariable String runId,
                              HttpServletRequest http,
                              @RequestHeader("Idempotency-Key") String idempotencyKey,
                              @Valid @RequestBody SelectionRequest request) {
        return commands.accept(runId, AuthenticatedUser.id(jwt), clientAddresses.resolve(http), idempotencyKey, AgentCommand.CommandType.SELECT,
                AgentCommand.QueueClass.TRANSACTION, new CommandPayload(null, null, request.skuId(), null, null, null, null));
    }

    @PostMapping("/runs/{runId}/clarifications")
    @ResponseStatus(HttpStatus.ACCEPTED)
    CommandAccepted clarify(@AuthenticationPrincipal Jwt jwt, @PathVariable String runId,
                               HttpServletRequest http,
                               @RequestHeader("Idempotency-Key") String idempotencyKey,
                               @Valid @RequestBody ClarificationRequest request) {
        return commands.accept(runId, AuthenticatedUser.id(jwt), clientAddresses.resolve(http), idempotencyKey, AgentCommand.CommandType.CLARIFY,
                AgentCommand.QueueClass.PLANNING, new CommandPayload(null, request.message(), null, null, null, null, null));
    }

    @PostMapping("/runs/{runId}/approvals")
    @ResponseStatus(HttpStatus.ACCEPTED)
    CommandAccepted approve(@AuthenticationPrincipal Jwt jwt, @PathVariable String runId,
                               HttpServletRequest http,
                               @RequestHeader("Idempotency-Key") String idempotencyKey,
                               @Valid @RequestBody ApprovalRequest request) {
        String userId = AuthenticatedUser.id(jwt);
        if (request.decision() == Decision.REJECT) return commands.accept(runId, userId, clientAddresses.resolve(http), idempotencyKey,
                AgentCommand.CommandType.REJECT, AgentCommand.QueueClass.CONTROL,
                new CommandPayload(null, null, null, null, null, null, null));
        if (request.decision() == null) throw new IllegalArgumentException("decision is required");
        if (request.snapshotId() == null || request.snapshotId().isBlank()
                || request.expectedSummaryHash() == null || request.expectedSummaryHash().isBlank()) {
            throw new IllegalArgumentException("approval requires snapshotId and expectedSummaryHash");
        }
        return commands.accept(runId, userId, clientAddresses.resolve(http), idempotencyKey, AgentCommand.CommandType.APPROVE,
                AgentCommand.QueueClass.TRANSACTION, new CommandPayload(null, null, null, request.snapshotId(),
                        request.expectedSummaryHash(), null, null));
    }

    @PostMapping("/runs/{runId}/constraint-relaxations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    CommandAccepted relax(@AuthenticationPrincipal Jwt jwt, @PathVariable String runId,
                             HttpServletRequest http,
                             @RequestHeader("Idempotency-Key") String idempotencyKey,
                             @Valid @RequestBody ConstraintRelaxationRequest request) {
        if (request.fields() == null || request.fields().isEmpty()) {
            throw new IllegalArgumentException("constraint relaxation must name at least one field");
        }
        var allowed = java.util.Set.of("query", "category", "budgetMax", "budgetMin", "preferredBrands",
                "excludedBrands", "requiredAttributes", "quantity", "deliveryBy");
        if (!allowed.containsAll(request.fields())) {
            throw new IllegalArgumentException("constraint relaxation contains a protected or unknown field");
        }
        return commands.accept(runId, AuthenticatedUser.id(jwt), clientAddresses.resolve(http), idempotencyKey, AgentCommand.CommandType.RELAX,
                AgentCommand.QueueClass.PLANNING, new CommandPayload(null, request.message(), null, null, null, null,
                        request.fields()));
    }

    @PostMapping("/runs/{runId}/cancellations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    CommandAccepted cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String runId,
                           HttpServletRequest http,
                           @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return commands.accept(runId, AuthenticatedUser.id(jwt), clientAddresses.resolve(http), idempotencyKey, AgentCommand.CommandType.CANCEL,
                AgentCommand.QueueClass.CONTROL, new CommandPayload(null, null, null, null, null, null, null));
    }

    public record StartRunRequest(@NotBlank @Size(max = 64) String conversationId,
                                  @NotBlank @Size(max = 4000) String message,
                                  @NotBlank @Size(max = 128) String addressId,
                                  @Valid ConstraintInput constraints) {
    }

    /** 外部请求模型有明确容量边界；领域模型仍专注表达业务含义，不承载 HTTP 校验注解。 */
    public record ConstraintInput(@Size(max = 500) String query,
                                  @Size(max = 64) String category,
                                  @Valid Money budgetMax,
                                  @Valid Money budgetMin,
                                  @Size(max = 20) List<@NotBlank @Size(max = 64) String> preferredBrands,
                                  @Size(max = 20) List<@NotBlank @Size(max = 64) String> excludedBrands,
                                  @Size(max = 20) Map<@NotBlank @Size(max = 64) String,
                                          @NotBlank @Size(max = 128) String> requiredAttributes,
                                  @Min(1) @Max(99) int quantity,
                                  LocalDate deliveryBy) {
    }

    public record SelectionRequest(@NotBlank @Size(max = 128) String skuId) {
    }

    public record ClarificationRequest(@NotBlank @Size(max = 4000) String message) {
    }

    public record ConstraintRelaxationRequest(@NotBlank @Size(max = 4000) String message,
                                              @Size(min = 1, max = 10)
                                              java.util.List<@NotBlank @Size(max = 64) String> fields) {
    }

    public record ApprovalRequest(Decision decision,
                                  @Size(max = 128) String snapshotId,
                                  @Size(max = 128) String expectedSummaryHash) {
    }

    public enum Decision { APPROVE, REJECT }
}
