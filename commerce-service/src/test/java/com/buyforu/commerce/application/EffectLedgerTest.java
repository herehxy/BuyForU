package com.buyforu.commerce.application;

import com.buyforu.commerce.port.model.CommerceModels.EffectContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * effect ledger 的失败语义必须与生产 {@code JdbcCommerceEngine} 一致。
 *
 * <p>生产把业务写入与 effect_record 放在同一个事务：业务失败则事务回滚，记录一并消失，
 * 因此"失败后重试"是真正重跑。测试替身若缓存并重放失败，就会比生产更严格，
 * 让一个在生产上会重跑成功的请求在测试里一直失败——这正是测试与生产分叉的典型形态。</p>
 */
class EffectLedgerTest {
    private static final String OPERATION = "PREPARE_CONFIRMABLE_ORDER";
    private static final String HASH = "hash-1";

    @Test
    void doesNotCacheFailuresSoARetryReruns() {
        EffectLedger ledger = new EffectLedger();
        EffectContext effect = effect("effect-1", "idem-1");
        AtomicInteger attempts = new AtomicInteger();

        CommerceException first = assertThrows(CommerceException.class, () -> ledger.execute(
                effect, OPERATION, HASH, () -> {
                    attempts.incrementAndGet();
                    throw new CommerceException("OUT_OF_STOCK", "insufficient inventory");
                }));
        assertEquals("OUT_OF_STOCK", first.code());

        String replayed = ledger.execute(effect, OPERATION, HASH, () -> {
            attempts.incrementAndGet();
            return "snapshot-2";
        });

        assertEquals(2, attempts.get(), "失败不落账，重试必须真正重跑");
        assertEquals("snapshot-2", replayed);
    }

    @Test
    void replaysSuccessfulResultsWithoutRerunning() {
        EffectLedger ledger = new EffectLedger();
        EffectContext effect = effect("effect-2", "idem-2");
        AtomicInteger attempts = new AtomicInteger();

        String first = ledger.execute(effect, OPERATION, HASH, () -> {
            attempts.incrementAndGet();
            return "snapshot-1";
        });
        String replay = ledger.execute(effect, OPERATION, HASH, () -> {
            attempts.incrementAndGet();
            return "should-never-be-computed";
        });

        assertEquals("snapshot-1", first);
        assertEquals("snapshot-1", replay);
        assertEquals(1, attempts.get(), "成功结果必须重放，不能再次产生副作用");
    }

    @Test
    void replaysThroughTheNetworkIdempotencyKeyEvenWhenTheEffectIdChanges() {
        EffectLedger ledger = new EffectLedger();
        AtomicInteger attempts = new AtomicInteger();

        String first = ledger.execute(effect("effect-a", "stable-client-key"), OPERATION, HASH, () -> {
            attempts.incrementAndGet();
            return "snapshot-1";
        });
        String replay = ledger.execute(effect("effect-b", "stable-client-key"), OPERATION, HASH, () -> {
            attempts.incrementAndGet();
            return "should-never-be-computed";
        });

        assertEquals("snapshot-1", first);
        assertEquals("snapshot-1", replay);
        assertEquals(1, attempts.get());
    }

    @Test
    void rejectsKeyReuseWithADifferentRequest() {
        EffectLedger ledger = new EffectLedger();
        EffectContext effect = effect("effect-3", "idem-3");
        ledger.execute(effect, OPERATION, HASH, () -> "snapshot-1");

        CommerceException conflict = assertThrows(CommerceException.class,
                () -> ledger.execute(effect, OPERATION, "another-hash", () -> "should-never-be-computed"));

        assertEquals("EFFECT_CONFLICT", conflict.code());
    }

    private static EffectContext effect(String effectId, String idempotencyKey) {
        return new EffectContext(effectId, idempotencyKey, "run-1", "prepare", 0, "u-1", "trace-1");
    }
}
