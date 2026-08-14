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

/** 已认证 API 的用户/IP 粗粒度保护：查询走只读桶，地址登记等直写走写入桶。 */
public final class ReadRateLimitFilter extends OncePerRequestFilter {
    private final RedisAdmissionController admission;
    private final ClientAddressResolver clientAddresses;
    public ReadRateLimitFilter(RedisAdmissionController admission, ClientAddressResolver clientAddresses) {
        this.admission = admission;
        this.clientAddresses = clientAddresses;
    }

    private static boolean directWrite(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && "/api/v1/addresses".equals(request.getRequestURI());
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        Authentication authentication = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String userId = jwt.getToken().getSubject();
            String address = clientAddresses.resolve(request);
            try {
                if ("GET".equals(request.getMethod()) && request.getRequestURI().startsWith("/api/")) {
                    admission.admitReadBestEffort(userId, address);
                } else if (directWrite(request)) {
                    // 地址登记不走 CommandService，必须在这里套写入令牌桶。
                    admission.admit(userId, address, com.buyforu.agent.concurrency.AgentCommand.QueueClass.TRANSACTION);
                }
            } catch (com.buyforu.agent.concurrency.CommandExceptions.AdmissionRejected rejected) {
                response.setStatus(429);
                response.setHeader("Retry-After", Long.toString(rejected.retryAfterSeconds()));
                response.setContentType("application/problem+json");
                response.getWriter().write("{\"title\":\"Request was not admitted\",\"status\":429,"
                        + "\"detail\":\"rate limit exceeded\"}");
                return;
            } catch (com.buyforu.agent.concurrency.CommandExceptions.CoordinationUnavailable ignored) {
                response.setStatus(503);
                response.setContentType("application/problem+json");
                response.getWriter().write("{\"title\":\"Traffic coordination unavailable\",\"status\":503,"
                        + "\"detail\":\"New expensive commands are temporarily unavailable; queries and cancellation remain available.\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
