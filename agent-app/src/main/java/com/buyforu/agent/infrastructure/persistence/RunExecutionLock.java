package com.buyforu.agent.infrastructure.persistence;

import com.buyforu.agent.application.RunExecutionCoordinator;
import org.springframework.stereotype.Component;

/**
 * 图内执行协调器。跨实例串行化已由命令 Worker 的短租约和 execution epoch 承担，
 * 因此这里绝不能再打开一个覆盖整个 LangGraph/LLM/MCP 调用的数据库事务。
 */
@Component
public class RunExecutionLock implements RunExecutionCoordinator {
    @Override
    public void execute(String runId, Runnable action) {
        action.run();
    }
}
