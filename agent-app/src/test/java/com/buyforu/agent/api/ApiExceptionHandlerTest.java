package com.buyforu.agent.api;

import com.buyforu.agent.application.RunStateConflictException;
import com.buyforu.agent.concurrency.CommandExceptions;
import com.buyforu.commerce.port.CommerceOperationException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证异常到 HTTP 状态码的映射规则和 ProblemDetail 的安全输出。
 */
class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Nested
    class CommerceFailureMapping {
        @Test
        void outOfStockMapsToConflict() {
            ProblemDetail detail = handler.commerceFailure(
                    new CommerceOperationException("OUT_OF_STOCK", "gone"));

            assertEquals(HttpStatus.CONFLICT.value(), detail.getStatus());
            assertEquals("OUT_OF_STOCK", detail.getProperties().get("code"));
        }

        @Test
        void budgetExceededMapsToConflict() {
            ProblemDetail detail = handler.commerceFailure(
                    new CommerceOperationException("BUDGET_EXCEEDED", "over budget"));

            assertEquals(HttpStatus.CONFLICT.value(), detail.getStatus());
        }

        @Test
        void addressUserMismatchMapsToForbidden() {
            ProblemDetail detail = handler.commerceFailure(
                    new CommerceOperationException("ADDRESS_USER_MISMATCH", "not yours"));

            assertEquals(HttpStatus.FORBIDDEN.value(), detail.getStatus());
        }

        @Test
        void skuNotFoundMapsToNotFound() {
            ProblemDetail detail = handler.commerceFailure(
                    new CommerceOperationException("SKU_NOT_FOUND", "missing"));

            assertEquals(HttpStatus.NOT_FOUND.value(), detail.getStatus());
        }

        @Test
        void snapshotExpiredMapsToConflict() {
            ProblemDetail detail = handler.commerceFailure(
                    new CommerceOperationException("SNAPSHOT_EXPIRED", "expired"));

            assertEquals(HttpStatus.CONFLICT.value(), detail.getStatus());
        }

        @Test
        void unknownCodeMapsToUnprocessableEntity() {
            ProblemDetail detail = handler.commerceFailure(
                    new CommerceOperationException("UNKNOWN_CUSTOM_CODE", "custom"));

            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY.value(), detail.getStatus());
        }

        @Test
        void detailMessageDoesNotLeakInternalInfo() {
            ProblemDetail detail = handler.commerceFailure(
                    new CommerceOperationException("OUT_OF_STOCK", "internal details here"));

            assertNotNull(detail.getDetail());
            assertFalse(detail.getDetail().contains("internal details"));
        }
    }

    @Nested
    class CommandExceptionMapping {
        @Test
        void idempotencyConflictMapsToConflict() {
            ProblemDetail detail = handler.idempotencyConflict(
                    new CommandExceptions.IdempotencyConflict());

            assertEquals(HttpStatus.CONFLICT.value(), detail.getStatus());
        }

        @Test
        void admissionRejectedMapsTo429WithRetryAfter() {
            ResponseEntity<ProblemDetail> response = handler.admissionRejected(
                    new CommandExceptions.AdmissionRejected("rate limited", 30));

            assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
            assertEquals("30", response.getHeaders().getFirst("Retry-After"));
        }

        @Test
        void coordinationUnavailableMapsTo503() {
            ProblemDetail detail = handler.coordinationUnavailable(
                    new CommandExceptions.CoordinationUnavailable(new RuntimeException("redis down")));

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), detail.getStatus());
        }
    }

    @Nested
    class OtherExceptionMapping {
        @Test
        void illegalArgumentMapsTo400() {
            ProblemDetail detail = handler.invalidRequest(
                    new IllegalArgumentException("bad input"));

            assertEquals(HttpStatus.BAD_REQUEST.value(), detail.getStatus());
        }

        @Test
        void runStateConflictMapsTo409() {
            ProblemDetail detail = handler.conflict(
                    new RunStateConflictException("wrong phase"));

            assertEquals(HttpStatus.CONFLICT.value(), detail.getStatus());
        }

        @Test
        void securityExceptionMapsToForbidden() {
            ProblemDetail detail = handler.forbidden(new SecurityException("not allowed"));

            assertEquals(HttpStatus.FORBIDDEN.value(), detail.getStatus());
        }

        @Test
        void unknownExceptionMapsTo500() {
            ProblemDetail detail = handler.internalFailure(new RuntimeException("unexpected"));

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), detail.getStatus());
            assertNotNull(detail.getDetail());
            assertFalse(detail.getDetail().contains("unexpected"));
        }

        @Test
        void invalidArgumentDetailSanitizesExceptionMessages() {
            // 包含 "Exception" 的消息应被替换为通用提示
            ProblemDetail detail = handler.invalidRequest(
                    new IllegalArgumentException("java.lang.NullPointerException at line 42"));

            assertEquals("Request is invalid.", detail.getDetail());
        }

        @Test
        void invalidArgumentDetailSanitizesPathTraversal() {
            ProblemDetail detail = handler.invalidRequest(
                    new IllegalArgumentException("../../../etc/passwd"));

            assertEquals("Request is invalid.", detail.getDetail());
        }

        @Test
        void validArgumentDetailPassesThrough() {
            ProblemDetail detail = handler.invalidRequest(
                    new IllegalArgumentException("quantity must be between 1 and 99"));

            assertEquals("quantity must be between 1 and 99", detail.getDetail());
        }
    }
}
