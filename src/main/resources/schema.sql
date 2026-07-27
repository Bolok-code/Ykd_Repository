CREATE TABLE IF NOT EXISTS chat_history (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    conversation_id VARCHAR(64) NOT NULL,
    message_type    VARCHAR(16) NOT NULL,
    text_content    TEXT NOT NULL,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_chat_history_conv
    ON chat_history(conversation_id, id);
