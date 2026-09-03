package com.buyforu.agent.concurrency;

import com.buyforu.commerce.port.CommerceOperationException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 覆盖租约续期判据与命令失败分类。
 *
 * 核心不变量：已运行时长本身不构成终止理由，只有命令期限届满或用户请求取消才停止续租。
 * 修复前 heartbeat 用 startedAt 加 90 秒硬阈值判定"卡住"，而 PLANNING 命令期限为 210 秒，
 * 导致所有超过 90 秒的合法规划任务被确定性误杀——且中断后直接进入 FAILED，不重试。
 */
class CommandWorkerTest {

    @Nested
    class LeaseRenewal {
        @Test
        void renewsWhileCommandStillWithinDeadline() {
            Instant now = Instant.parse("2026-09-03T10:00:00Z");
            Instant deadlineAt = now.plusSeconds(30);

            assertTrue(CommandWorker.shouldRenewLease(deadlineAt, now));
        }

        @Test
        void renewsEvenAfterRunningBeyondNinetySeconds() {
            // 回归锁：命令已运行 180 秒，但只要期限未到就必须继续续租。
            // 修复前这里会因 90 秒硬阈值返回 false，把合法的慢规划任务判死。
            Instant startedAt = Instant.parse("2026-09-03T10:00:00Z");
            Instant now = startedAt.plusSeconds(180);
            Instant deadlineAt = startedAt.plusSeconds(210);

            assertTrue(CommandWorker.shouldRenewLease(deadlineAt, now),
                    "运行 180 秒但期限未到的 PLANNING 命令必须继续续租");
        }

        @Test
        void renewsWhenDeadlineMatchesFullPlanningWindow() {
            // PLANNING 期限为 210 秒；命令在 209 秒时仍必须被续租。
            Instant startedAt = Instant.parse("2026-09-03T10:00:00Z");
            Instant now = startedAt.plusSeconds(209);
            Instant deadlineAt = startedAt.plusSeconds(210);

            assertTrue(CommandWorker.shouldRenewLease(deadlineAt, now));
        }

        @Test
        void stopsRenewingOnceDeadlinePassed() {
            Instant now = Instant.parse("2026-09-03T10:00:00Z");
            Instant deadlineAt = now.minusSeconds(1);

            assertFalse(CommandWorker.shouldRenewLease(deadlineAt, now));
        }

        @Test
        void stopsRenewingExactlyAtDeadline() {
            Instant now = Instant.parse("2026-09-03T10:00:00Z");

            assertFalse(CommandWorker.shouldRenewLease(now, now),
                    "期限到达即停止续租，不能因边界包含而无限延长");
        }

        @Test
        void treatsMissingDeadlineAsRenewable() {
            Instant now = Instant.parse("2026-09-03T10:00:00Z");

            assertTrue(CommandWorker.shouldRenewLease(null, now));
        }
    }

    @Nested
    class FailureClassification {
        @Test
        void interruptedCallIsClassifiedAsDeadlineExceeded() {
            // 中断语义是"被终止"而非"外部超时"，错误码必须与后者区分，
            // 否则运维无法从 error_code 识别命令是被期限终止还是真的出错。
            assertEquals("COMMAND_DEADLINE_EXCEEDED", CommandWorker.classify(
                    new DependencyExecutor.DependencyInterruptedException(
                            DependencyExecutor.Dependency.DEEPSEEK, new InterruptedException())));
        }

        @Test
        void interruptedCallNestedInWrapperStillClassifiedAsDeadlineExceeded() {
            assertEquals("COMMAND_DEADLINE_EXCEEDED", CommandWorker.classify(
                    new IllegalStateException("graph",
                            new DependencyExecutor.DependencyInterruptedException(
                                    DependencyExecutor.Dependency.MCP_WRITE, new InterruptedException()))));
        }

        @Test
        void timeoutIsStillClassifiedAsDependencyTimeout() {
            // 中断判定不能抢走 Timeout 的既有分类。
            assertEquals("DEPENDENCY_TIMEOUT", CommandWorker.classify(
                    new DependencyExecutor.DependencyTimeoutException(
                            DependencyExecutor.Dependency.DEEPSEEK, null)));
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
    }

    static final class McpContractException extends RuntimeException { }
    static final class McpInfrastructureException extends RuntimeException { }
}
