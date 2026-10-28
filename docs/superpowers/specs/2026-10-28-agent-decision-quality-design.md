# Agent Decision Quality Hardening

## Intent

BuyForU already has strong transaction safety, idempotency and recovery. The next risk is decision quality: the dispatcher under-fills workers, natural-language product search loses relevant candidates, and an invalid model plan terminates the run without a bounded repair attempt.

## Goals

- Make each dispatch cycle use all currently available worker permits while keeping CONTROL commands ahead of background lanes.
- Recover CONTROL commands that entered `RETRY_WAIT`.
- Treat free-text product query as a relevance signal rather than an unconditional hard filter.
- Normalize Chinese category and common attribute expressions before querying Commerce.
- Preserve Commerce candidate order unless a ranking preference actually changes it.
- Apply a user's preferred brands even when the model omits `BRAND_PREFERENCE`.
- Repair one invalid model plan inside the same command, then fail with a stable error if repair also fails.
- Add a current-time anchor for relative delivery dates and basic planning usage/retrieval metrics.

## Non-Goals

- LLM-as-a-Judge, evaluation traces and human calibration.
- Webhook replay protection, MCP user binding and frontend test infrastructure.
- Cleaning correctness ledgers such as `effect_record`.
- A broad `ShoppingWorkflowService` or `JdbcCommerceEngine` refactor.
- Allowing the model to choose graph topology or transaction write tools.

## Constraints

- PostgreSQL remains the source of truth for commands, leases and transactions.
- Commerce remains the source of truth for price, stock and delivery.
- The model never receives write-tool callbacks.
- Search query differences may broaden recall only after hard category, brand, attribute, stock, budget and delivery constraints are applied.
- Existing public APIs stay compatible unless an internal record needs a compatibility constructor.
- Every production behavior change starts with a failing test.

## Decisions

- Search result carries `queryMatched` so callers can distinguish exact textual relevance from a hard-constraint fallback without changing HTTP contracts.
- Category and query normalization live in `commerce-port`, so JDBC and in-memory Commerce implementations share one vocabulary.
- Plan repair is invoked at most once per planning operation. The repair prompt receives the rejected plan and validator error but not raw Commerce or user secrets.
- The default planning time zone is `Asia/Shanghai`, configurable through `buyforu.planning.zone-id`.
- Dispatch batches are bounded by each lane's configured worker count and stop on an unavailable user permit to avoid a hot spin.

## Acceptance Criteria

- One `dispatchBatch` call can submit as many commands as available permits, not just one.
- CONTROL is queried before PLANNING and TRANSACTION in a dispatch cycle.
- `controlReady` includes due `RETRY_WAIT` commands and excludes expired deadlines.
- `想买一台轻薄本` and `5000元以内的笔记本` produce useful query tokens and match catalog candidates.
- A query that matches no product text falls back to hard-constrained candidates with `queryMatched=false`.
- Tablet, monitor, keyboard and mouse aliases resolve to canonical categories.
- A model plan rejected by `PlanSpecValidator` is repaired once and the run continues.
- A second invalid plan fails deterministically and records a validation metric.
- Existing backend and frontend verification commands remain green.
