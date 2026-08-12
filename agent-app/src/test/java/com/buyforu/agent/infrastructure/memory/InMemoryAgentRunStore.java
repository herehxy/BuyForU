package com.buyforu.agent.infrastructure.memory;

import com.buyforu.agent.application.AgentRunStore;
import com.buyforu.agent.domain.ShoppingAgentState;

import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/** 单元测试专用仓库；该类位于 test 源集，绝不会打入生产 JAR。 */
public final class InMemoryAgentRunStore implements AgentRunStore {
    private final Map<String, ShoppingAgentState> states = new ConcurrentHashMap<>();

    @Override
    public ShoppingAgentState save(ShoppingAgentState state) {
        states.put(state.runId(), state);
        return state;
    }

    @Override
    public Optional<ShoppingAgentState> find(String runId) {
        return Optional.ofNullable(states.get(runId));
    }

    @Override
    public List<ShoppingAgentState> findRecentByUser(String userId, int limit) {
        return states.values().stream().filter(state -> state.userId().equals(userId))
                .sorted(Comparator.comparing(ShoppingAgentState::updatedAt).reversed())
                .limit(limit).toList();
    }

    @Override
    public ShoppingAgentState update(String runId, UnaryOperator<ShoppingAgentState> mutation) {
        return states.compute(runId, (ignored, current) -> {
            if (current == null) throw new IllegalArgumentException("run not found: " + runId);
            return mutation.apply(current);
        });
    }
}
