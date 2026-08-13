package com.buyforu.agent.application;

/** 图推进的应用层协调扩展点；生产串行化由 CommandWorker 的短租约和 execution epoch 完成。 */
public interface RunExecutionCoordinator {
    void execute(String runId, Runnable action);
}
