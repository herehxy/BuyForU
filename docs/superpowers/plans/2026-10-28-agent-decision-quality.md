# Agent Decision Quality Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the highest-impact agent decision and dispatch defects without weakening the existing transaction boundary.

**Architecture:** Keep the fixed graph and Commerce write boundary unchanged. Add bounded, testable policies around dispatch, catalog normalization/search and plan validation, with metrics for the new fallback paths.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Mockito, PostgreSQL/pgvector, Testcontainers, React/TypeScript.

**Spec:** `docs/superpowers/specs/2026-10-28-agent-decision-quality-design.md`

## Global Constraints

- Commerce remains authoritative for price, stock and delivery.
- LLM output never selects graph topology or transaction write tools.
- Query mismatch may broaden recall only after hard constraints pass.
- Existing public APIs remain source-compatible where practical.
- Each task follows RED -> GREEN -> full relevant suite.

## Review Focus

- A busy planning queue must not starve cancellation commands.
- A query containing negation or stopwords must not silently broaden into an unrelated purchase.
- A model-invented hard constraint must remain visible to future provenance work even if this iteration does not implement full provenance.
- Existing snapshots in JSON may contain enum values or ranking preferences that are no longer preferred but must still deserialize.
- Category aliases must not collapse different real categories into one value.

---

### Task 1: Fair Dispatch And CONTROL Recovery

**Files:**
- Modify: `agent-app/src/main/java/com/buyforu/agent/concurrency/CommandWorker.java`
- Modify: `agent-app/src/main/java/com/buyforu/agent/concurrency/CommandRepository.java`
- Test: `agent-app/src/test/java/com/buyforu/agent/concurrency/CommandWorkerPermitTest.java`
- Test: `agent-app/src/test/java/com/buyforu/agent/concurrency/CommandWorkerDispatchTest.java`

**Interfaces:**
- Consumes: `CommandRepository.controlReady(int)`, `RedisFairQueue.poll(QueueClass)`, `RunLeaseRepository.claim(...)`.
- Produces: `int dispatchBatch(QueueClass lane, Semaphore permits, ExecutorService executor, int maxCommands)`.

- [ ] **Step 1: Write failing batch-dispatch and control-priority tests**

Add tests that submit three commands with three permits in one `dispatchBatch` call, verify one poll per command, and verify `commands.controlReady(...)` is invoked before background `fairQueue.poll(...)` in `dispatch()`.

- [ ] **Step 2: Run the focused tests and verify RED**

Run: `./mvnw -pl agent-app -Dtest=CommandWorkerPermitTest,CommandWorkerDispatchTest test`

Expected: FAIL because `dispatchBatch` does not exist and `dispatch()` has the old lane order.

- [ ] **Step 3: Implement bounded batch dispatch and control-first ordering**

Keep `dispatchLane(...)` as a one-command compatibility wrapper. `dispatch()` calls a control helper first, then batches PLANNING and TRANSACTION up to their permit counts. Stop a batch on an empty queue or a user-permit conflict.

- [ ] **Step 4: Update CONTROL readiness SQL**

Change `controlReady` to include `status IN ('QUEUED','RETRY_WAIT')`, `available_at<=now()` and `deadline_at>now()`.

- [ ] **Step 5: Run the focused tests and verify GREEN**

Run the same focused test command. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add agent-app/src/main/java/com/buyforu/agent/concurrency/CommandWorker.java \
  agent-app/src/main/java/com/buyforu/agent/concurrency/CommandRepository.java \
  agent-app/src/test/java/com/buyforu/agent/concurrency/CommandWorkerPermitTest.java \
  agent-app/src/test/java/com/buyforu/agent/concurrency/CommandWorkerDispatchTest.java
git commit -m "fix: fill worker lanes fairly each dispatch cycle"
```

---

### Task 2: Natural-Language Search And Stable Ranking

**Files:**
- Create: `commerce-port/src/main/java/com/buyforu/commerce/port/CatalogSearchText.java`
- Create: `commerce-port/src/main/java/com/buyforu/commerce/port/CatalogVocabulary.java`
- Modify: `commerce-port/src/main/java/com/buyforu/commerce/port/CatalogAttributeNormalizer.java`
- Modify: `commerce-port/src/main/java/com/buyforu/commerce/port/model/CommerceModels.java`
- Modify: `commerce-service/src/main/java/com/buyforu/commerce/application/JdbcCommerceEngine.java`
- Modify: `commerce-service/src/test/java/com/buyforu/commerce/application/InMemoryCommerceEngine.java`
- Modify: `agent-app/src/main/java/com/buyforu/agent/application/SpringAiPlanningModel.java`
- Modify: `agent-app/src/main/java/com/buyforu/agent/application/ShoppingWorkflowService.java`
- Test: `commerce-port/src/test/java/com/buyforu/commerce/port/CatalogSearchTextTest.java`
- Test: `commerce-port/src/test/java/com/buyforu/commerce/port/CatalogVocabularyTest.java`
- Test: `commerce-service/src/test/java/com/buyforu/commerce/application/InMemoryCommerceEngineTest.java`
- Test: `agent-app/src/test/java/com/buyforu/agent/application/ShoppingWorkflowServiceTest.java`

**Interfaces:**
- Consumes: `SearchRequest`, `ProductCandidate`, `CatalogAttributeNormalizer.skuAttributes(Map)`.
- Produces: `CatalogSearchText.tokens(String)`, `CatalogSearchText.matches(String,String)`, `CatalogVocabulary.category(String)`, and `SearchResult(..., boolean queryMatched)` with a two-argument compatibility constructor.

- [ ] **Step 1: Write failing tokenizer/category/attribute tests**

Assert Chinese sentence tokenization for `想买一台轻薄本` and `5000元以内的笔记本`, category aliases for tablet/monitor/keyboard/mouse, and ANC/wireless value normalization.

- [ ] **Step 2: Run focused tests and verify RED**

Run: `./mvnw -pl commerce-port -Dtest=CatalogSearchTextTest,CatalogVocabularyTest,CatalogAttributeNormalizerTest test`

Expected: FAIL because the new classes and value normalization do not exist.

- [ ] **Step 3: Implement shared query/category/attribute normalization**

Generate whole CJK terms and bigrams after removing shopping stopwords; limit tokens to 12. Keep query matching as an OR relevance signal.

- [ ] **Step 4: Write failing Commerce search tests**

Assert:
- `降噪耳机` finds headphone candidates.
- An unmatched query falls back to hard-constrained candidates with `queryMatched=false`.
- Excluded brands and required attributes still filter fallback candidates.

- [ ] **Step 5: Implement two-pass JDBC search**

Push category, stock, excluded brands, required-attribute JSON and query-token predicates into SQL before `LIMIT`. Apply budget/delivery in Java, then retry without query tokens only when query filtering produced no candidate. Mirror the same behavior in `InMemoryCommerceEngine`.

- [ ] **Step 6: Write failing ranking tests**

Assert no-preference ranking preserves Commerce order, and preferred brands are ranked first even when the model omits `BRAND_PREFERENCE`.

- [ ] **Step 7: Implement stable ranking**

Use a stable sort over the incoming candidate list. Prepend `BRAND_PREFERENCE` when `preferredBrands` is non-empty. Remove the `productId` tie-breaker.

- [ ] **Step 8: Run focused tests and verify GREEN**

Run: `./mvnw -pl commerce-port,commerce-service,agent-app -am -Dtest=CatalogSearchTextTest,CatalogVocabularyTest,CatalogAttributeNormalizerTest,InMemoryCommerceEngineTest,ShoppingWorkflowServiceTest test`

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add commerce-port commerce-service agent-app
git commit -m "feat: improve natural-language product search"
```

---

### Task 3: Bounded Plan Repair, Input Bounds And Planning Metrics

**Files:**
- Modify: `agent-app/src/main/java/com/buyforu/agent/application/PlanningModel.java`
- Modify: `agent-app/src/main/java/com/buyforu/agent/application/SpringAiPlanningModel.java`
- Modify: `agent-app/src/main/java/com/buyforu/agent/application/ShoppingWorkflowService.java`
- Modify: `agent-app/src/main/java/com/buyforu/agent/domain/PlanSpecValidator.java`
- Create: `agent-app/src/main/java/com/buyforu/agent/application/PlanningCallMetrics.java`
- Modify: `agent-app/src/main/resources/application.yml`
- Test: `agent-app/src/test/java/com/buyforu/agent/application/ShoppingWorkflowServiceTest.java`
- Test: `agent-app/src/test/java/com/buyforu/agent/application/PlanningCallMetricsTest.java`
- Test: `agent-app/src/test/java/com/buyforu/agent/application/SpringAiPlanningModelLogicTest.java`
- Test: `agent-app/src/test/java/com/buyforu/agent/domain/PlanSpecValidatorTest.java`

**Interfaces:**
- Consumes: `PlanSpecValidator.validate(PlanSpec)`, `PlanSpec`, `ShoppingConstraints`.
- Produces: default `PlanningModel.repairPlan(request, constraints, rejectedPlan, validationError)`, `PlanningCallMetrics`, and `SpringAiPlanningModel.planningTimestamp(Clock,ZoneId)`.

- [ ] **Step 1: Write failing repair tests**

Add a model that returns an invalid plan first and a valid plan from `repairPlan`; assert the run reaches `SEARCHING`. Add a second test where repair is also invalid and assert a stable `PlanSpecValidationException`.

- [ ] **Step 2: Run focused repair tests and verify RED**

Run: `./mvnw -pl agent-app -Dtest=ShoppingWorkflowServiceTest test`

Expected: FAIL because the service never calls a repair method.

- [ ] **Step 3: Implement one-shot repair**

Validate initial, clarification and search-replan outputs through one helper. On validation failure, call `repairPlan` once, validate again, and throw a stable exception if repair fails.

- [ ] **Step 4: Write failing validator-bound and time-anchor tests**

Assert query/category/inquiry/rationale length limits and verify `2026-08-12T08:00:00Z` formats as `2026-08-12T16:00+08:00` for `Asia/Shanghai`.

- [ ] **Step 5: Implement bounds and time context**

Add explicit maximum lengths to `PlanSpecValidator` and include the configured current timestamp in the planning prompt.

- [ ] **Step 6: Write failing metrics tests**

Assert retrieval hit/miss, model success/failure and prompt/completion token counters using `SimpleMeterRegistry`.

- [ ] **Step 7: Capture ChatResponse usage**

Replace `.entity(PlanSpec.class)` with `.responseEntity(PlanSpec.class)`, read usage metadata, and record it through `PlanningCallMetrics`. Do not persist raw prompts or responses.

- [ ] **Step 8: Run focused tests and verify GREEN**

Run: `./mvnw -pl agent-app -Dtest=ShoppingWorkflowServiceTest,PlanningCallMetricsTest,SpringAiPlanningModelLogicTest,PlanSpecValidatorTest test`

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add agent-app
git commit -m "feat: repair invalid plans and record planning metrics"
```

---

### Task 4: Documentation And Whole-Project Verification

**Files:**
- Modify: `README.md`
- Modify: `docs/CODE_GUIDE.md`

**Interfaces:**
- Consumes: all behavior from Tasks 1-3.
- Produces: documentation that matches runtime behavior.

- [ ] **Step 1: Update current implementation and code guide**

Document fair batch dispatch, CONTROL retry readiness, CJK/soft-query search, stable ranking and one-shot plan repair.

- [ ] **Step 2: Run backend unit tests**

Run: `./mvnw -q test`

Expected: exit 0.

- [ ] **Step 3: Run frontend verification**

Run: `cd web && npm run typecheck && npm run build`

Expected: exit 0.

- [ ] **Step 4: Run integration tests when Docker is available**

Run: `./mvnw -q -Pintegration verify`

Expected: exit 0, or document the unavailable Docker daemon as an explicit verification gap.

- [ ] **Step 5: Commit**

```bash
git add README.md docs/CODE_GUIDE.md
git commit -m "docs: describe decision-quality hardening"
```
