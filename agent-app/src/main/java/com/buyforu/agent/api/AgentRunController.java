package com.buyforu.agent.api;

import com.buyforu.agent.application.GraphShoppingWorkflow;
import com.buyforu.agent.domain.PlanSpec.ShoppingConstraints;
import com.buyforu.agent.domain.ShoppingAgentState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 购物任务 HTTP API。userId 只从已校验 JWT 的 sub 获取，绝不接受客户端自报身份。
 * Controller 负责协议校验，实际状态转换交给 GraphShoppingWorkflow。
 */
@RestController
@RequestMapping("/api/v1")
public class AgentRunController {
    private final GraphShoppingWorkflow workflow;

    public AgentRunController(GraphShoppingWorkflow workflow) {
        this.workflow = workflow;
    }

    @PostMapping("/runs")
    ShoppingAgentState start(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody StartRunRequest request) {
        ShoppingConstraints supplied = request.constraints();
        ShoppingConstraints secured = new ShoppingConstraints(
                supplied == null ? null : supplied.query(), supplied == null ? null : supplied.category(),
                supplied == null ? null : supplied.budgetMax(),
                supplied == null ? null : supplied.preferredBrands(),
                supplied == null ? null : supplied.excludedBrands(),
                supplied == null ? null : supplied.requiredAttributes(),
                supplied == null ? 0 : supplied.quantity(), request.addressId(),
                supplied == null ? null : supplied.deliveryBy(), supplied == null ? 1 : supplied.version());
        return workflow.start(request.conversationId(), AuthenticatedUser.id(jwt), request.message(), secured,
                request.idempotencyKey());
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
    ShoppingAgentState select(@AuthenticationPrincipal Jwt jwt, @PathVariable String runId,
                              @Valid @RequestBody SelectionRequest request) {
        return workflow.selectCandidate(runId, AuthenticatedUser.id(jwt), request.skuId());
    }

    @PostMapping("/runs/{runId}/clarifications")
    ShoppingAgentState clarify(@AuthenticationPrincipal Jwt jwt, @PathVariable String runId,
                               @Valid @RequestBody ClarificationRequest request) {
        return workflow.clarify(runId, AuthenticatedUser.id(jwt), request.message());
    }

    @PostMapping("/runs/{runId}/approvals")
    ShoppingAgentState approve(@AuthenticationPrincipal Jwt jwt, @PathVariable String runId,
                               @Valid @RequestBody ApprovalRequest request) {
        String userId = AuthenticatedUser.id(jwt);
        if (request.decision() == Decision.REJECT) return workflow.reject(runId, userId);
        if (request.decision() == null) throw new IllegalArgumentException("decision is required");
        if (request.snapshotId() == null || request.snapshotId().isBlank()
                || request.expectedSummaryHash() == null || request.expectedSummaryHash().isBlank()) {
            throw new IllegalArgumentException("approval requires snapshotId and expectedSummaryHash");
        }
        return workflow.approve(runId, userId, request.snapshotId(), request.expectedSummaryHash());
    }

    @PostMapping("/runs/{runId}/constraint-relaxations")
    ShoppingAgentState relax(@AuthenticationPrincipal Jwt jwt, @PathVariable String runId,
                             @Valid @RequestBody ConstraintRelaxationRequest request) {
        return workflow.relax(runId, AuthenticatedUser.id(jwt), request.message());
    }

    @PostMapping("/runs/{runId}/cancellations")
    ShoppingAgentState cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String runId) {
        return workflow.cancel(runId, AuthenticatedUser.id(jwt));
    }

    public record StartRunRequest(@NotBlank @Size(max = 128) String conversationId,
                                  @NotBlank @Size(max = 4000) String message,
                                  @NotBlank @Size(max = 128) String addressId,
                                  @NotBlank @Size(max = 128) String idempotencyKey,
                                  ShoppingConstraints constraints) {
    }

    public record SelectionRequest(@NotBlank @Size(max = 128) String skuId) {
    }

    public record ClarificationRequest(@NotBlank @Size(max = 4000) String message) {
    }

    public record ConstraintRelaxationRequest(@NotBlank @Size(max = 4000) String message) {
    }

    public record ApprovalRequest(Decision decision, String snapshotId, String expectedSummaryHash) {
    }

    public enum Decision { APPROVE, REJECT }
}
