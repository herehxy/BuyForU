package com.buyforu.agent.application;

import com.buyforu.agent.domain.ShoppingAgentState;

import java.util.Optional;
import java.util.function.UnaryOperator;

/** ShoppingAgentState 的持久化端口；应用层不依赖具体数据库。 */
public interface AgentRunStore {
    ShoppingAgentState save(ShoppingAgentState state);

    Optional<ShoppingAgentState> find(String runId);

    java.util.List<ShoppingAgentState> findRecentByUser(String userId, int limit);

    ShoppingAgentState update(String runId, UnaryOperator<ShoppingAgentState> mutation);
}
