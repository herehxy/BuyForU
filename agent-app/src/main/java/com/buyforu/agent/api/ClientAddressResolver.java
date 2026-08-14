package com.buyforu.agent.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 解析限流使用的客户端地址。只有请求确实来自显式配置的反向代理时才读取 X-Forwarded-For，
 * 防止公网客户端伪造转发头绕过 IP 粗粒度保护。
 */
@Component
public final class ClientAddressResolver {
    private final Set<String> trustedProxies;

    public ClientAddressResolver(@Value("${buyforu.security.trusted-proxies:}") String configured) {
        trustedProxies = Arrays.stream(configured.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    public String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (!trustedProxies.contains(remote)) return remote;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) return remote;
        String client = forwarded.split(",", 2)[0].trim();
        return client.isEmpty() || client.length() > 64 ? remote : client;
    }
}
