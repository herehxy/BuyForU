package com.buyforu.agent.application;

import com.buyforu.commerce.port.CommerceGateway;
import com.buyforu.commerce.port.model.CommerceModels.CancelOrderCommand;
import com.buyforu.commerce.port.model.CommerceModels.EffectContext;
import com.buyforu.commerce.port.model.CommerceModels.Order;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 订单取消用例：把"用户想撤销这笔订单"转换为一次带 effect 上下文的 Commerce 写操作。
 *
 * <p><b>与 Run 编排解耦</b>：取消是订单聚合上的操作。用户可能在订单页发起取消，此时产生订单的
 * Run 早已结束，因此这里不经过购物图、不占用命令队列，也绝不修改任何历史 Run——Run 记录的是
 * "下单时发生了什么"，订单的当前状态由交易系统单独维护。</p>
 *
 * <p><b>幂等键完全由 (userId, orderId) 确定性派生</b>：既不混入 runId，也不依赖客户端提供的键。任何客户端
 * 在任何时刻重复提交同一订单的取消，都会命中同一个 effectId 并从账本重放结果。反过来，如果锚在
 * runId 上，同一订单经两个 Run 取消会派生出两个幂等键，库存就会被回补两次——这是静默的数据损坏，
 * 因此这里的锚点选择是正确性要求，不是风格偏好。</p>
 *
 * <p>带上 userId 是为了让 effectId 成为"请求身份"的完整函数，与交易侧 requestHash 的口径一致：
 * 若 effectId 只认 orderId，非属主发起的取消会先撞上账本的键复用检查、返回 EFFECT_CONFLICT，
 * 而不是本应给出的 ORDER_USER_MISMATCH。两个键都只决定<b>报什么错</b>，不决定能否重复回补——
 * 重复回补由交易侧两道状态条件更新兜底。</p>
 */
@Service
public class OrderCancellationService {
    private final CommerceGateway commerce;

    public OrderCancellationService(CommerceGateway commerce) {
        this.commerce = commerce;
    }

    public Order cancel(String userId, String orderId) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId is required");
        }
        String effectId = effectId(userId, orderId);
        return commerce.cancelOrder(new CancelOrderCommand(orderId, userId),
                new EffectContext(effectId, effectId, effectId, "cancel-order", 0, userId, effectId));
    }

    /** 单独抽出便于测试锁定派生规则：改动这里会改变线上重放的命中结果。 */
    static String effectId(String userId, String orderId) {
        return hash("order-cancel", userId, orderId);
    }

    private static String hash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(String.join("\u001f", values)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
