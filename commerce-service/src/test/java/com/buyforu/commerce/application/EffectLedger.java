package com.buyforu.commerce.application;

import com.buyforu.commerce.port.model.CommerceModels.EffectContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 测试用进程内交易实现的幂等账本，语义必须与生产 {@code JdbcCommerceEngine} 对齐。
 *
 * <p>生产侧业务方法与 effect_record 的写入在同一个事务里：业务失败 → 事务回滚 → 记录一并消失，
 * 所以"失败后重试"在生产上就是<b>真正重跑</b>。早期实现把 {@code CommerceException} 也缓存下来、
 * 并在重放时原样抛出，等于让测试替身比生产更严格——一个在生产上会重跑成功的请求，
 * 在测试里会一直拿到那次缓存的旧失败，测试与生产就此分叉。</p>
 *
 * <p>本类位于 test 源集：只有 {@link InMemoryCommerceEngine} 使用它。留在主源集既是永不执行的
 * 死代码，也随时可能被误当作生产实现接进配置。</p>
 */
final class EffectLedger {
    private final Map<String, EffectRecord> effects = new ConcurrentHashMap<>();
    private final Map<String, EffectRecord> idempotencyKeys = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    synchronized <T> T execute(EffectContext context, String operation, String requestHash, Supplier<T> action) {
        // effectId 或网络幂等键命中时，先核验请求哈希，再重放原结果。
        EffectRecord existing = effects.get(context.effectId());
        if (existing == null) existing = idempotencyKeys.get(context.idempotencyKey());
        if (existing != null) {
            if (!existing.operation.equals(operation) || !existing.requestHash.equals(requestHash)) {
                throw new CommerceException("EFFECT_CONFLICT",
                        "effectId or idempotencyKey was reused with a different operation or request");
            }
            effects.putIfAbsent(context.effectId(), existing);
            return (T) existing.result;
        }

        // 失败不落账：事务回滚后没有记录，重试必然是重跑。
        T result = action.get();
        EffectRecord completed = new EffectRecord(operation, requestHash, result);
        effects.put(context.effectId(), completed);
        idempotencyKeys.put(context.idempotencyKey(), completed);
        return result;
    }

    private record EffectRecord(String operation, String requestHash, Object result) {
    }
}
