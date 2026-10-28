package com.buyforu.agent.application;

/** 规划模型在初始调用和单次修复后都没有产出可执行计划。 */
public final class PlanSpecValidationException extends RuntimeException {
    public PlanSpecValidationException(String message, Throwable cause) {
        super(message, cause);
    }

    public String code() {
        return "PLAN_VALIDATION_FAILED";
    }
}
