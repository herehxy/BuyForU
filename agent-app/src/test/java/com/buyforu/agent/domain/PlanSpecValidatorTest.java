package com.buyforu.agent.domain;

import com.buyforu.agent.application.DeterministicPlanningModel;
import com.buyforu.commerce.port.model.CommerceModels.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 LLM 即使输出合法 JSON，也不能关闭人工放宽审批或加入任意任务。 */
class PlanSpecValidatorTest {
    @Test
    void acceptsOnlyFixedReadTasksAndSafeFallbackPolicy() {
        PlanSpec.ShoppingConstraints constraints = constraints();
        PlanSpec plan = new DeterministicPlanningModel().createPlan("5000 元以内的笔记本", constraints);
        PlanSpec validated = new PlanSpecValidator().validate(plan);

        assertTrue(validated.readTasks().contains(PlanSpec.ReadTask.SEARCH_PRODUCTS));
        assertTrue(validated.fallbackPolicy().requireApprovalForConstraintRelaxation());
        assertEquals(2, validated.fallbackPolicy().maxSearchReplans());
    }

    @Test
    void rejectsSilentConstraintRelaxation() {
        PlanSpec safe = new DeterministicPlanningModel().createPlan("laptop", constraints());
        PlanSpec unsafe = new PlanSpec(safe.intentType(), safe.normalizedConstraints(), safe.clarification(),
                safe.searchStrategy(), safe.readTasks(), safe.rankingPreferences(),
                new PlanSpec.FallbackPolicy(true, 2, false), "unsafe");

        assertThrows(IllegalArgumentException.class, () -> new PlanSpecValidator().validate(unsafe));
    }

    private PlanSpec.ShoppingConstraints constraints() {
        return new PlanSpec.ShoppingConstraints("", "laptop", Money.cny("5000"), List.of(),
                List.of("Excluded"), Map.of("memory", "16GB"), 1, "addr-1",
                LocalDate.now().plusDays(1), 1);
    }
}
