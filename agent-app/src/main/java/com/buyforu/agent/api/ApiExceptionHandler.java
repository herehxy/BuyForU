package com.buyforu.agent.api;

import com.buyforu.commerce.port.CommerceOperationException;
import com.buyforu.agent.application.RunStateConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 将领域错误转换为 RFC 9457 ProblemDetail，并附加 requestId；未知异常只写服务日志，不泄露堆栈。
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(CommerceOperationException.class)
    ProblemDetail commerceFailure(CommerceOperationException exception) {
        HttpStatus status = switch (exception.code()) {
            case "ADDRESS_USER_MISMATCH", "RESERVATION_USER_MISMATCH", "APPROVAL_USER_MISMATCH",
                 "EFFECT_USER_MISMATCH" ->
                    HttpStatus.FORBIDDEN;
            case "SKU_NOT_FOUND", "RESERVATION_NOT_FOUND", "SNAPSHOT_NOT_FOUND",
                 "DELIVERY_ZONE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "OUT_OF_STOCK", "RESERVATION_NOT_ACTIVE", "SNAPSHOT_EXPIRED", "APPROVAL_EXPIRED",
                 "EFFECT_CONFLICT", "EFFECT_IN_PROGRESS" -> HttpStatus.CONFLICT;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        ProblemDetail detail = ProblemDetail.forStatus(status);
        detail.setTitle("Commerce operation rejected");
        detail.setDetail(exception.getMessage());
        detail.setProperty("code", exception.code());
        addRequestId(detail);
        return detail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidRequest(IllegalArgumentException exception) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setTitle("Invalid request");
        detail.setDetail(exception.getMessage());
        addRequestId(detail);
        return detail;
    }

    @ExceptionHandler(RunStateConflictException.class)
    ProblemDetail conflict(RunStateConflictException exception) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        detail.setTitle("Invalid run state");
        detail.setDetail(exception.getMessage());
        addRequestId(detail);
        return detail;
    }

    @ExceptionHandler(SecurityException.class)
    ProblemDetail forbidden(SecurityException exception) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        detail.setTitle("Forbidden");
        detail.setDetail(exception.getMessage());
        addRequestId(detail);
        return detail;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail internalFailure(Exception exception) {
        log.error("Unhandled API failure", exception);
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        detail.setTitle("Internal service failure");
        detail.setDetail("The request could not be completed. Use the server trace for diagnostics.");
        addRequestId(detail);
        return detail;
    }

    private static void addRequestId(ProblemDetail detail) {
        String requestId = MDC.get(RequestCorrelationFilter.MDC_KEY);
        if (requestId != null) detail.setProperty("requestId", requestId);
    }
}
