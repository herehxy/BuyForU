package com.buyforu.agent.api;

import org.springframework.security.oauth2.jwt.Jwt;

/** 从已验证 JWT 提取稳定用户标识的唯一入口，避免各 Controller 采用不同 claim。 */
final class AuthenticatedUser {
    private AuthenticatedUser() {
    }

    static String id(Jwt jwt) {
        // OIDC 的 sub 是不可由请求体伪造的主体标识，不能使用客户端传入的 userId。
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new SecurityException("authenticated token has no subject");
        }
        return subject;
    }
}
