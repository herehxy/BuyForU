package com.buyforu.agent.application;

import com.buyforu.commerce.port.CommerceGateway;
import com.buyforu.commerce.port.model.CommerceModels.DeliveryAddress;
import com.buyforu.commerce.port.model.CommerceModels.EffectContext;
import com.buyforu.commerce.port.model.CommerceModels.RegisterAddressCommand;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** 配送地址用例：把用户请求转换为带 effect 上下文的 Commerce 写操作。 */
@Service
public class DeliveryAddressService {
    private final CommerceGateway commerce;

    public DeliveryAddressService(CommerceGateway commerce) {
        this.commerce = commerce;
    }

    public DeliveryAddress register(String userId, String zoneCode, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        // 确定性 effectId 使同一用户、同一请求键的重复登记安全返回原结果。
        String effectId = hash("address", userId, idempotencyKey);
        return commerce.registerAddress(new RegisterAddressCommand(userId, zoneCode),
                new EffectContext(effectId, effectId, effectId, "register-address", 0, userId, effectId));
    }

    public List<DeliveryAddress> list(String userId) {
        return commerce.listAddresses(userId);
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
