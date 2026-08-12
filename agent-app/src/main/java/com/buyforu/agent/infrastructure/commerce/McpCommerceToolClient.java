package com.buyforu.agent.infrastructure.commerce;

/**
 * Protocol-specific client boundary implemented by an MCP SDK transport.
 * Keeping this marker separate prevents an HTTP client from being accidentally
 * presented as MCP and prevents MCP types leaking into the application layer.
 */
public interface McpCommerceToolClient extends CommerceToolClient {
}
