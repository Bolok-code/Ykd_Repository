ALTER TABLE conversation_message
    ADD COLUMN intent VARCHAR(50) NULL AFTER content,
    ADD COLUMN city VARCHAR(100) NULL AFTER intent,
    ADD COLUMN target_date DATE NULL AFTER city,
    ADD INDEX idx_conversation_user_intent
        (user_id, intent, id);