# 修复方案：heartbeat 陈旧判定与命令期限对齐

> 目标缺陷：P2（`CommandWorker.heartbeat()` 90 秒硬阈值误杀长时间运行的 PLANNING 命令）
> 附带修复：魔法数字违反规范、误杀与主动取消无法区分
> 基线 HEAD：`0d96ffc` ｜ 方案日期：2026-09-03

---

## 1. 失败场景（先定义，再改代码）

以下场景均在单测可验证范围内，不需要 Docker。

### 场景 A（主缺陷）：合法长任务被确定性误杀

1. 用户提交 PLANNING 命令，`deadlineAt = now + 210s`。
2. DeepSeek 响应慢或触发三级 Replan，命令持续运行。
3. `t = 90s`：`heartbeat()` 计算 `staleBefore = now - 90s`，因 `startedAt < staleBefore` 判定"卡住"。
4. 停止续租并 `worker.interrupt()`。
5. worker 阻塞在 `DependencyExecutor.future.get()`，抛 `InterruptedException` → 包装为 `DependencyInterruptedException`。
6. 该类型**不在** `CommandWorker.execute()` 的可重试分支内，落入 `catch (RuntimeException)`。
7. 命令被 `markFailed('COMMAND_EXECUTION_FAILED')`，**`attempts` 不递增、不重试**。
8. 用户看到"任务执行失败，请使用错误码和 requestId 排查"。

**性质**：确定性失败。任务需求超过 90 秒时，重试多少次都在同一位置被杀。

### 场景 B（衍生）：误杀与用户主动取消不可区分

用户主动取消同样走 `interrupt → DependencyInterruptedException → COMMAND_EXECUTION_FAILED` 路径。
两者产生完全相同的 `error_code`，运维无法从数据库或指标中识别场景 A。

### 场景 C（衍生）：违反项目自身规范

`ConcurrencyProperties` 类注释承诺"代码中不散落不可运维的魔法数字"，`minusSeconds(90)` 正违反该承诺。

---

## 2. 必须成立的不变量

修复后，以下五条必须全部成立，并由测试锁定：

| # | 不变量 |
|---|---|
| I1 | 只要命令未超过 `deadlineAt`，无论已运行多久，都必须持续获得续租 |
| I2 | 停止续租的唯一理由是：`cancel_requested = true` **或** `deadlineAt <= now` |
| I3 | 全系统租约陈活性判定统一以 `lease_until` / `deadlineAt` 为准，不存在第二套时间判据 |
| I4 | 所有期限与容量参数必须可从 `ConcurrencyProperties` 配置，代码中无裸魔法数字 |
| I5 | 被中断的命令必须可归类为 `CANCELLED` / `EXPIRED` / `FAILED` 之一，且在指标上可区分 |

---

## 3. 方案对比

| 方案 | 判据 | 改动面 | 优点 | 缺点 |
|---|---|---|---|---|
| **A. 用 `deadlineAt` 判定** | 命令期限是否届满 | `CommandWorker` 两处方法 | 零新增配置、零数据库迁移；直接复用 `CommandService` 已定义的 210/50/15 秒；消除魔法数字 | 假死线程会占用租约至期限届满（PLANNING 最长 210 秒） |
| **B. 新增 `last_heartbeat_at` 列 + 可配置阈值** | 最后心跳时间 | Flyway 迁移 + 表结构 + 判据 | 可配置"无进展"阈值 | 循环论证：heartbeat 自身不续租则该列不更新，无法区分"主动停止"与"线程假死"；需迁移，风险高 |
| **C. A + 业务进展信号** | 期限届满 OR 无进展超阈值 | A + `ExecutionContext` 埋点 + 图节点改造 | 能提前识别假死 | 改动面显著扩大，需在图节点推进处埋点 |

**推荐：方案 A 为必做项，方案 C 列为后续可选增强。**

理由：方案 A 以最小改动消除确定性不可用、语义不一致与规范违背三项问题；方案 C 解决的"假死占满 210 秒"属次要问题，且 210 秒为有界上界、不会无限占用，可分期实施。方案 B 存在循环论证缺陷，不采纳。

---

## 4. 详细设计（方案 A）

### 4.1 变更一：`CommandWorker.heartbeat()` 判据替换

**现状**（`CommandWorker.java:89-106`）：

```java
@Scheduled(fixedDelayString = "${buyforu.concurrency.lease-heartbeat:10s}", scheduler = "leaseScheduler")
void heartbeat() {
    Instant staleBefore = Instant.now().minusSeconds(90);          // 硬编码魔法数字
    activeLeases.forEach((commandId, lease) -> {
        boolean cancelRequested = leases.cancellationRequested(lease);
        Instant startedAt = commands.find(commandId).map(AgentCommand::startedAt).orElse(null);
        if (cancelRequested || !shouldRenewLease(startedAt, staleBefore)) {   // 用首次开始时间
            inFlight.cancel(commandId);
            Thread worker = activeThreads.get(commandId);
            if (worker != null) worker.interrupt();
            return;
        }
        if (!leases.heartbeat(lease, Instant.now().plus(properties.leaseDuration()))) {
            activeLeases.remove(commandId);
        }
    });
}
```

**改为**：

```java
@Scheduled(fixedDelayString = "${buyforu.concurrency.lease-heartbeat:10s}", scheduler = "leaseScheduler")
void heartbeat() {
    Instant now = Instant.now();
    activeLeases.forEach((commandId, lease) -> {
        boolean cancelRequested = leases.cancellationRequested(lease);
        Instant deadlineAt = commands.find(commandId).map(AgentCommand::deadlineAt).orElse(null);
        // 只有期限届满或用户请求取消才停止续租；已运行时长本身不构成终止理由（I1、I2）。
        if (cancelRequested || !shouldRenewLease(deadlineAt, now)) {
            inFlight.cancel(commandId);
            Thread worker = activeThreads.get(commandId);
            if (worker != null) worker.interrupt();
            return;
        }
        if (!leases.heartbeat(lease, now.plus(properties.leaseDuration()))) {
            activeLeases.remove(commandId);
        }
    });
}
```

### 4.2 变更二：`shouldRenewLease` 语义重新定义

**现状**（`CommandWorker.java:237-239`）：

```java
static boolean shouldRenewLease(Instant startedAt, Instant staleBefore) {
    return startedAt == null || !startedAt.isBefore(staleBefore);
}
```

**改为**：

```java
/** 续租的唯一判据是命令是否仍在期限内；已运行时长不影响续租（I1）。 */
static boolean shouldRenewLease(Instant deadlineAt, Instant now) {
    return deadlineAt == null || deadlineAt.isAfter(now);
}
```

### 4.3 变更三：中断结果的分类（修复场景 B）

在 `CommandWorker.execute()` 中，**在 `catch (RuntimeException)` 之前**新增分支（`DependencyInterruptedException` 是 `RuntimeException` 子类，catch 顺序必须在前）：

```java
} catch (DependencyExecutor.DependencyInterruptedException interrupted) {
    // 被 heartbeat 中断：区分"用户主动取消"与"期限届满终止"（I5）。
    if (leases.cancellationRequested(lease)) {
        commands.markCancelled(command.commandId(), "RUN_CANCEL_REQUESTED");
        events.append(command.runId(), command.commandId(), "command.cancelled",
                Map.of("code", "RUN_CANCEL_REQUESTED"));
    } else {
        commands.markFailed(command.commandId(), "COMMAND_DEADLINE_EXCEEDED", safeMessage(interrupted));
        meters.counter("buyforu_command_deadline_terminated_total",
                "queue_class", command.queueClass().name()).increment();
        events.append(command.runId(), command.commandId(), "command.failed",
                Map.of("code", "COMMAND_DEADLINE_EXCEEDED"));
    }
} catch (RuntimeException failure) {
    // 既有的通用失败分支保持不变
}
```

**关键约束**：不可用 `markExpired()` 代替——其 SQL 限定 `status IN ('QUEUED','RETRY_WAIT')`，对 `RUNNING` 命令无效，会静默漏更新。

### 4.4 变更四：`safeMessage` 补充文案

```java
case "COMMAND_DEADLINE_EXCEEDED" -> "任务处理超时，请稍后重试";
```

### 4.5 变更五（可选优化）：消除每轮心跳的数据库查询

当前每次心跳对每个活跃命令执行一次 `commands.find()`。可将 `deadlineAt` 在 `claim` 时写入 `Lease` record（`AgentCommand.deadlineAt()` 在 claim 处已知），heartbeat 直接读取，省去查询。

- 收益：20 个 planning worker 场景减少约 2 QPS 的心跳查询。
- 代价：`Lease` 是 public record，改动构造会影响 `RunLeaseRepository.claim` 与相关测试。
- 建议：**本次不做**，列为独立优化项，避免扩大改动面。

### 4.6 不需要数据库迁移

方案 A 仅改变判定逻辑，`deadline_at` 列已存在于 `agent_command` 表。这是相对方案 B 的关键优势。

---

## 5. 测试计划

### 5.1 必须修改的既有测试（固化了错误行为）

`CommandWorkerTest.staleOrMissingStartTimeControlsLeaseRenewal` 当前断言：

```java
assertFalse(CommandWorker.shouldRenewLease(Instant.parse("2026-08-14T10:01:00Z"), staleBefore));
```

该断言把"按首次开始时间判定"固化为正确行为。**修复必须同步改写此测试**，否则会立即失败。

### 5.2 新增测试

| 测试 | 锁定的不变量 | 预期 |
|---|---|---|
| `renewsLeaseForCommandRunningBeyondNinetySeconds` | I1 | 命令已运行 180 秒、`deadlineAt` 仍有余量 → **继续续租**（本条直接锁死 P2，是最重要的回归测试） |
| `stopsRenewingWhenDeadlinePassed` | I2 | `deadlineAt` 已过 → 停止续租 |
| `stopsRenewingWhenCancellationRequested` | I2 | `cancel_requested = true` → 停止续租 |
| `treatsNullDeadlineAsRenewable` | I2 | `deadlineAt == null` → 继续续租（保持既有防御语义） |
| `deadlineTerminationRecordsDeadlineExceeded` | I5 | 期限届满中断 → `error_code = COMMAND_DEADLINE_EXCEEDED`，而非通用的 `COMMAND_EXECUTION_FAILED` |
| `cancellationMarksCommandCancelled` | I5 | 用户取消中断 → `status = CANCELLED` |

### 5.3 集成测试（需 Docker，非本次必做）

补充"PLANNING 命令运行 90–210 秒仍能正常完成"的端到端用例，验证真实调度下的行为。

---

## 6. 风险与回滚

| 风险 | 评估 | 缓解 |
|---|---|---|
| 假死线程占用租约至期限届满 | 低。PLANNING 上界 210 秒，有界；崩溃实例由 `recoverExpired()` 兜底 | 监控 `buyforu_lease_recovered_total` 与新增的 `buyforu_command_deadline_terminated_total` |
| `shouldRenewLease` 签名变更导致测试失败 | 必然发生，属预期 | 同步改写（该测试固化的是缺陷行为） |
| 中断分类误判 | 低。`cancellationRequested` 直接读数据库标志位 | 用场景化测试覆盖两种分支 |
| 心跳停止后命令未被及时回收 | 低。`recoverExpired()` 每 5 秒扫描 `lease_until <= now` | 无需额外处理 |

**回滚**：改动集中在 `CommandWorker` 的 `heartbeat()`、`shouldRenewLease()` 与 `execute()` 三个方法内，无数据库迁移、无 Schema 变更，可单点回滚至 `0d96ffc` 行为。

---

## 7. 执行清单

1. 修改 `CommandWorker.shouldRenewLease()` 签名为 `(Instant deadlineAt, Instant now)`。
2. 修改 `CommandWorker.heartbeat()` 判据，删除 `minusSeconds(90)`。
3. 在 `CommandWorker.execute()` 中 `catch (RuntimeException)` **之前**插入 `DependencyInterruptedException` 分支。
4. 在 `safeMessage()` 中补充 `COMMAND_DEADLINE_EXCEEDED` 文案。
5. 改写 `CommandWorkerTest.staleOrMissingStartTimeControlsLeaseRenewal`。
6. 新增 §5.2 的 6 条测试。
7. 运行 `./mvnw test` 验证全绿（基线 151 通过，改后应 ≥156）。
8. 更新 `docs/CORRECTNESS_REVIEW_FIXES.md`，按项目惯例记录失败场景、不变量与修复方式。
