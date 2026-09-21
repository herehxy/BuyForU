package com.buyforu.commerce.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Commerce 不是用户入口，只接受 Agent 服务持有的长随机令牌；健康检查保持匿名可用。
 */
@Configuration
public class McpSecurityConfiguration {
    @Bean
    SecurityFilterChain commerceSecurity(HttpSecurity http,
                                         @Value("${buyforu.mcp.service-token}") String serviceToken,
                                         @Value("${buyforu.metrics.token:}") String metricsToken) throws Exception {
        if (serviceToken == null || serviceToken.length() < 32) {
            throw new IllegalStateException("COMMERCE_MCP_SERVICE_TOKEN must contain at least 32 characters");
        }
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new ServiceTokenFilter(serviceToken, metricsToken),
                        AbstractPreAuthenticatedProcessingFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/mcp", "/mcp/**", "/actuator/prometheus").permitAll()
                        .anyRequest().denyAll())
                .build();
    }

    private static final class ServiceTokenFilter extends OncePerRequestFilter {
        private final byte[] mcpToken;
        private final byte[] metricsToken;

        private ServiceTokenFilter(String serviceToken, String metricsToken) {
            this.mcpToken = serviceToken.getBytes(StandardCharsets.UTF_8);
            this.metricsToken = metricsToken == null || metricsToken.isBlank()
                    ? null : metricsToken.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !request.getRequestURI().equals("/mcp")
                    && !request.getRequestURI().startsWith("/mcp/")
                    && !request.getRequestURI().equals("/actuator/prometheus");
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            boolean prometheus = request.getRequestURI().equals("/actuator/prometheus");
            byte[] expected = prometheus && metricsToken != null ? metricsToken : mcpToken;
            String header = prometheus && metricsToken != null
                    ? "X-BuyForU-Metrics-Token" : "X-BuyForU-Service-Token";
            String token = request.getHeader(header);
            // 常量时间比较，避免通过响应耗时逐字节猜测服务令牌。
            if (token == null || !MessageDigest.isEqual(expected, token.getBytes(StandardCharsets.UTF_8))) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "invalid service credential");
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
