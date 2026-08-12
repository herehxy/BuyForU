package com.buyforu.agent.infrastructure.persistence;

import com.buyforu.agent.application.ConversationMemory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** PostgreSQL 对话记忆实现，同时强制 conversationId 的用户所有权。 */
@Repository
public class JdbcConversationMemory implements ConversationMemory {
    private final JdbcTemplate jdbc;

    public JdbcConversationMemory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void appendUserMessage(String conversationId, String userId, String content) {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("message content is required");
        // 先创建会话，再在锁定行上核验 owner；相同 conversationId 不能被另一用户接管。
        jdbc.update("""
                INSERT INTO agent_schema.conversation(conversation_id, user_id)
                VALUES (?, ?) ON CONFLICT (conversation_id) DO NOTHING
                """, conversationId, userId);
        String owner = jdbc.queryForObject("""
                SELECT user_id FROM agent_schema.conversation WHERE conversation_id = ? FOR UPDATE
                """, String.class, conversationId);
        if (!userId.equals(owner)) throw new SecurityException("conversation belongs to another user");
        jdbc.update("""
                INSERT INTO agent_schema.message(message_id, conversation_id, role, content)
                VALUES (?, ?, 'USER', ?)
                """, UUID.randomUUID().toString(), conversationId, content);
    }

    @Override
    public List<String> recentUserMessages(String conversationId, String userId, int limit) {
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("message limit must be between 1 and 20");
        // SQL 为了高效 LIMIT 先倒序读取，返回应用层前恢复为时间正序。
        List<String> descending = jdbc.query("""
                SELECT m.content FROM agent_schema.message m
                JOIN agent_schema.conversation c ON c.conversation_id = m.conversation_id
                WHERE m.conversation_id = ? AND c.user_id = ? AND m.role = 'USER'
                ORDER BY m.created_at DESC, m.message_id DESC LIMIT ?
                """, (result, row) -> result.getString(1), conversationId, userId, limit);
        return descending.reversed();
    }
}
