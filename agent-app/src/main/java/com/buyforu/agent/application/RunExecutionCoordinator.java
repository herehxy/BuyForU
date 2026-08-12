package com.buyforu.agent.application;

/** 保证同一 run 的图推进串行执行；生产实现使用 PostgreSQL advisory lock。 */
public interface RunExecutionCoordinator {
    void execute(String runId, Runnable action);
}
