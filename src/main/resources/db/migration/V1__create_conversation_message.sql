CREATE TABLE conversation_message (
                                      id BIGINT NOT NULL AUTO_INCREMENT,
                                      user_id VARCHAR(191) NOT NULL,
                                      role VARCHAR(20) NOT NULL,
                                      content TEXT NOT NULL,
                                      created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

                                      PRIMARY KEY (id),
                                      INDEX idx_conversation_user_id (user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;