package com.buyforu.agent.infrastructure.commerce;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 为指定的 Commerce MCP 连接注入服务凭证，不把凭证暴露给领域层或 Tool 参数。 */
@Configuration
public class McpClientConfiguration {
    @Bean
    McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> commerceCredentials(
            @Value("${buyforu.mcp.service-token}") String serviceToken) {
        if (serviceToken == null || serviceToken.length() < 32) {
            throw new IllegalStateException("COMMERCE_MCP_SERVICE_TOKEN must contain at least 32 characters");
        }
        return (name, builder) -> {
            if ("commerce".equals(name)) {
                builder.httpRequestCustomizer((request, method, uri, body, context) ->
                        request.header("X-BuyForU-Service-Token", serviceToken));
            }
        };
    }
}
