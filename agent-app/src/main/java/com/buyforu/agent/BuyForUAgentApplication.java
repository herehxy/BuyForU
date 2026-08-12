package com.buyforu.agent;

import com.buyforu.agent.infrastructure.commerce.McpCommerceGatewayAdapter;
import com.buyforu.agent.infrastructure.commerce.McpCommerceToolClient;
import com.buyforu.commerce.port.CommerceGateway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Agent 服务启动入口。
 * 这里完成最外层依赖装配：应用层依赖 CommerceGateway，实际运行时绑定到 MCP Adapter。
 */
@SpringBootApplication
public class BuyForUAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(BuyForUAgentApplication.class, args);
    }

    @Bean
    CommerceGateway commerceGateway(McpCommerceToolClient client) {
        // 业务层只看 CommerceGateway，MCP SDK 类型不会向应用层泄漏。
        return new McpCommerceGatewayAdapter(client);
    }
}
