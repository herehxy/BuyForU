package com.buyforu.agent.application;

/** 用户命令与当前人工等待阶段不匹配，对外映射为 HTTP 409。 */
public class RunStateConflictException extends RuntimeException {
    public RunStateConflictException(String message) {
        super(message);
    }
}
