package com.buyforu.agent.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 测试专用会话记忆；复现生产实现的用户所有权和最近消息语义。 */
final class InMemoryConversationMemory implements ConversationMemory {
    private final Map<String, List<String>> messages = new HashMap<>();
    private final Map<String, String> owners = new HashMap<>();

    @Override
    public void appendUserMessage(String conversationId, String userId, String content) {
        String owner = owners.putIfAbsent(conversationId, userId);
        if (owner != null && !owner.equals(userId)) throw new SecurityException("conversation belongs to another user");
        messages.computeIfAbsent(conversationId, ignored -> new ArrayList<>()).add(content);
    }

    @Override
    public List<String> recentUserMessages(String conversationId, String userId, int limit) {
        if (!userId.equals(owners.get(conversationId))) throw new SecurityException("conversation belongs to another user");
        List<String> values = messages.getOrDefault(conversationId, List.of());
        return List.copyOf(values.subList(Math.max(0, values.size() - limit), values.size()));
    }
}
