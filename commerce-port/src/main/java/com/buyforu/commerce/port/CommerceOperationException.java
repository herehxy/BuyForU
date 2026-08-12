package com.buyforu.commerce.port;

/** 可跨 CommerceGateway 传播的稳定领域错误，code 用于协议层映射状态码和恢复策略。 */
public class CommerceOperationException extends RuntimeException {
    private final String code;

    public CommerceOperationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
