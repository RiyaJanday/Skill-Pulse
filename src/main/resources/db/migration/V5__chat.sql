-- Study chat: conversations and messages are stored on the server, so the model only ever sees a history that
-- the server wrote itself (a browser can no longer forge earlier "assistant" turns).
-- category is the router's label for a LEARNER message (STUDY, GREETING, MIXED, OFF_TOPIC); it stays NULL for
-- assistant messages. The admin "refused messages" view reads it.
-- status is OK for a complete message and STOPPED for a reply that was cut short (stop button, dropped connection).
CREATE TABLE IF NOT EXISTS chat_conversations (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    title VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_chat_conversations_user ON chat_conversations(user_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS chat_messages (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES chat_conversations(id) ON DELETE CASCADE,
    role VARCHAR(12) NOT NULL,
    content TEXT NOT NULL,
    category VARCHAR(16),
    engine VARCHAR(120),
    status VARCHAR(12) NOT NULL DEFAULT 'OK',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_chat_messages_conversation ON chat_messages(conversation_id, id);
CREATE INDEX IF NOT EXISTS idx_chat_messages_category ON chat_messages(category, id DESC);
