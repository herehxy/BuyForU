package com.buyforu.agent.api;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 验证客户端地址解析逻辑：信任代理时才读取 X-Forwarded-For，否则使用远程地址。
 */
class ClientAddressResolverTest {

    @Test
    void returnsRemoteAddressWhenNoTrustedProxies() {
        ClientAddressResolver resolver = new ClientAddressResolver("");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.50");

        assertEquals("203.0.113.50", resolver.resolve(request));
    }

    @Test
    void returnsRemoteAddressWhenRequestNotFromTrustedProxy() {
        ClientAddressResolver resolver = new ClientAddressResolver("10.0.0.1");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.50");

        assertEquals("203.0.113.50", resolver.resolve(request));
    }

    @Test
    void readsForwardedHeaderWhenRequestFromTrustedProxy() {
        ClientAddressResolver resolver = new ClientAddressResolver("10.0.0.1");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.50, 10.0.0.2");

        assertEquals("203.0.113.50", resolver.resolve(request));
    }

    @Test
    void fallsBackToRemoteWhenForwardedHeaderMissing() {
        ClientAddressResolver resolver = new ClientAddressResolver("10.0.0.1");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);

        assertEquals("10.0.0.1", resolver.resolve(request));
    }

    @Test
    void fallsBackToRemoteWhenForwardedHeaderBlank() {
        ClientAddressResolver resolver = new ClientAddressResolver("10.0.0.1");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn("  ");

        assertEquals("10.0.0.1", resolver.resolve(request));
    }

    @Test
    void fallsBackToRemoteWhenForwardedClientEmpty() {
        ClientAddressResolver resolver = new ClientAddressResolver("10.0.0.1");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn(", 10.0.0.2");

        assertEquals("10.0.0.1", resolver.resolve(request));
    }

    @Test
    void fallsBackToRemoteWhenForwardedClientTooLong() {
        ClientAddressResolver resolver = new ClientAddressResolver("10.0.0.1");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        String longAddress = "a".repeat(65);
        when(request.getHeader("X-Forwarded-For")).thenReturn(longAddress);

        assertEquals("10.0.0.1", resolver.resolve(request));
    }

    @Test
    void supportsMultipleTrustedProxies() {
        ClientAddressResolver resolver = new ClientAddressResolver("10.0.0.1, 10.0.0.2, 10.0.0.3");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.2");
        when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.5");

        assertEquals("198.51.100.5", resolver.resolve(request));
    }

    @Test
    void extractsOnlyFirstAddressFromForwardedChain() {
        ClientAddressResolver resolver = new ClientAddressResolver("10.0.0.1");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.5, 10.0.0.2, 172.16.0.1");

        assertEquals("198.51.100.5", resolver.resolve(request));
    }

    @Test
    void acceptsForwardedClientAtMaxLength() {
        ClientAddressResolver resolver = new ClientAddressResolver("10.0.0.1");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        String maxAddress = "a".repeat(64);
        when(request.getHeader("X-Forwarded-For")).thenReturn(maxAddress);

        assertEquals(maxAddress, resolver.resolve(request));
    }
}
