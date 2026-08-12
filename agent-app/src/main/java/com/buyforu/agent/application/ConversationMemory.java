package com.buyforu.agent.application;

import java.util.List;

/** 按用户隔离的对话记忆端口，仅保存规划需要的用户消息。 */
public interface ConversationMemory {
    void appendUserMessage(String conversationId, String userId, String content);

    List<String> recentUserMessages(String conversationId, String userId, int limit);
}
