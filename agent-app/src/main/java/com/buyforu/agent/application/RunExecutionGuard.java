package com.buyforu.agent.application;

/**
 * 判断某个 run 是否仍被当前命令之外的 Worker 持有活租约。
 *
 * <p>CONTROL 命令在进入工作流之前也会领取租约，因此必须排除它自己；
 * 否则取消命令会永远误判为“下单仍在执行”。</p>
 */
@FunctionalInterface
public interface RunExecutionGuard {
    boolean hasConflictingLiveLease(String runId);
}
