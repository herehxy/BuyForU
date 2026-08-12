package com.buyforu.agent.infrastructure.persistence;

import com.buyforu.agent.application.RunExecutionCoordinator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;

/**
 * 跨进程的单 run 执行锁。多实例 Agent 收到同一任务命令时，只允许一个实例推进状态图。
 */
@Component
public class RunExecutionLock implements RunExecutionCoordinator {
    private final DataSource dataSource;

    public RunExecutionLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void execute(String runId, Runnable action) {
        // 锁与 action 使用同一连接事务持有；action 结束后提交/回滚即可自动释放锁。
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            lock(connection, runId);
            try {
                action.run();
                connection.commit();
            } catch (RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (RuntimeException runtime) {
            throw runtime;
        } catch (Exception checked) {
            throw new IllegalStateException("could not acquire run execution lock", checked);
        }
    }

    private static void lock(Connection connection, String runId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            statement.setString(1, "agent-run:" + runId);
            statement.execute();
        }
    }

}
