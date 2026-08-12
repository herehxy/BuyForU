package com.buyforu.agent.api;

import com.buyforu.agent.concurrency.RedisAdmissionController;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 已认证 GET 请求的用户/IP 粗粒度保护；只使用容器解析的 remoteAddr，不信任客户端转发头。 */
public final class ReadRateLimitFilter extends OncePerRequestFilter {
    private final RedisAdmissionController admission;
    public ReadRateLimitFilter(RedisAdmissionController admission) { this.admission = admission; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        Authentication authentication = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if ("GET".equals(request.getMethod()) && request.getRequestURI().startsWith("/api/")
                && authentication instanceof JwtAuthenticationToken jwt) {
            try {
                admission.admitReadBestEffort(jwt.getToken().getSubject(), request.getRemoteAddr());
            } catch (com.buyforu.agent.concurrency.CommandExceptions.AdmissionRejected rejected) {
                response.setStatus(429);
                response.setHeader("Retry-After", Long.toString(rejected.retryAfterSeconds()));
                response.setContentType("application/problem+json");
                response.getWriter().write("{\"title\":\"Request was not admitted\",\"status\":429,"
                        + "\"detail\":\"read rate limit exceeded\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
