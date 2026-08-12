package com.buyforu.agent.concurrency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 证明远程调用适配器不能在数据库事务仍活跃时执行。 */
class NetworkCallGuardTest {
    @AfterEach
    void clearThreadState() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void rejectsNetworkCallInsideTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThrows(IllegalStateException.class,
                () -> NetworkCallGuard.assertNoTransaction("DeepSeek"));
    }

    @Test
    void permitsNetworkCallAfterTransactionConnectionWasReleased() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertDoesNotThrow(() -> NetworkCallGuard.assertNoTransaction("DeepSeek"));
    }
}
