package com.buyforu.commerce.application;

import com.buyforu.commerce.port.model.CommerceModels.EffectContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 测试/进程内交易实现使用的 effect ledger 算法参考。
 * 生产 JdbcCommerceEngine 使用数据库 effect_record 和事务锁实现相同语义。
 */
final class EffectLedger {
    private final Map<String, EffectRecord> effects = new ConcurrentHashMap<>();
    private final Map<String, EffectRecord> idempotencyKeys = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    synchronized <T> T execute(EffectContext context, String operation, String requestHash, Supplier<T> action) {
        // effectId 或网络幂等键命中时，先核验请求哈希，再重放原结果/原失败。
        EffectRecord existing = effects.get(context.effectId());
        if (existing == null) existing = idempotencyKeys.get(context.idempotencyKey());
        if (existing != null) {
            if (!existing.operation.equals(operation) || !existing.requestHash.equals(requestHash)) {
                throw new CommerceException("EFFECT_CONFLICT",
                        "effectId or idempotencyKey was reused with a different operation or request");
            }
            effects.putIfAbsent(context.effectId(), existing);
            if (existing.failure != null) throw existing.failure;
            return (T) existing.result;
        }

        try {
            T result = action.get();
            EffectRecord completed = new EffectRecord(operation, requestHash, result, null);
            effects.put(context.effectId(), completed);
            idempotencyKeys.put(context.idempotencyKey(), completed);
            return result;
        } catch (CommerceException failure) {
            EffectRecord failed = new EffectRecord(operation, requestHash, null, failure);
            effects.put(context.effectId(), failed);
            idempotencyKeys.put(context.idempotencyKey(), failed);
            throw failure;
        }
    }

    private record EffectRecord(String operation, String requestHash, Object result, CommerceException failure) {
    }
}
