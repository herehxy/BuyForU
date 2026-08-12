package com.buyforu.commerce.application;

import com.buyforu.commerce.port.CommerceOperationException;

/** Commerce 内部领域异常；消息带稳定 code，便于 MCP 客户端恢复为 CommerceOperationException。 */
public class CommerceException extends CommerceOperationException {

    public CommerceException(String code, String message) {
        super(code, "[" + code + "] " + message);
    }
}
