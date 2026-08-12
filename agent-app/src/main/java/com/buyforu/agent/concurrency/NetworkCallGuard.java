package com.buyforu.agent.concurrency;

import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 网络适配器的强制边界：发现数据库事务仍然活跃时立即失败，防止连接池被远程等待耗尽。 */
public final class NetworkCallGuard {
    private NetworkCallGuard() { }

    public static void assertNoTransaction(String dependency) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(dependency + " network call attempted inside database transaction");
        }
    }
}
