package com.buyforu.agent.concurrency;

import com.buyforu.commerce.port.CommerceOperationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandWorkerTest {
    @Test
    void staleOrMissingStartTimeControlsLeaseRenewal() {
        Instant staleBefore = Instant.parse("2026-08-14T10:01:30Z");
        assertTrue(CommandWorker.shouldRenewLease(null, staleBefore));
        assertTrue(CommandWorker.shouldRenewLease(Instant.parse("2026-08-14T10:02:00Z"), staleBefore));
        assertFalse(CommandWorker.shouldRenewLease(Instant.parse("2026-08-14T10:01:00Z"), staleBefore));
    }

    @Test
    void classifyWalksCauseChainForMcpAndCommerceFailures() {
        assertEquals("MCP_CONTRACT_MISMATCH", CommandWorker.classify(
                new RuntimeException("graph", new McpContractException())));
        assertEquals("COMMERCE_UNAVAILABLE", CommandWorker.classify(
                new IllegalStateException("wrap", new McpInfrastructureException())));
        assertEquals("OUT_OF_STOCK", CommandWorker.classify(
                new RuntimeException(new CommerceOperationException("OUT_OF_STOCK", "gone"))));
        assertEquals("COMMAND_EXECUTION_FAILED", CommandWorker.classify(new IllegalStateException("other")));
    }

    static final class McpContractException extends RuntimeException { }
    static final class McpInfrastructureException extends RuntimeException { }
}
