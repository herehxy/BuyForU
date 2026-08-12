package com.buyforu.agent.application;

import com.buyforu.agent.domain.PlanSpec;

/**
 * 结构化规划模型端口。三种方法对应初始计划、硬约束不变的搜索重写和用户批准后的约束变更。
 */
public interface PlanningModel {
    PlanSpec createPlan(String request, PlanSpec.ShoppingConstraints constraints);

    PlanSpec replan(String request, PlanSpec.ShoppingConstraints currentConstraints,
                    String failureReason, int attempt);

    PlanSpec relaxConstraints(String request, PlanSpec.ShoppingConstraints currentConstraints,
                              String explicitUserInstruction);
}
