package com.buyforu.commerce.infrastructure;

import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Outbox 发网时如果还握着数据库事务，连接会被 HTTP 拖死。 */
final class NetworkCallGuard {
    private NetworkCallGuard() { }

    static void assertNoTransaction(String work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(work + " network call attempted inside database transaction");
        }
    }
}
