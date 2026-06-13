-- Multi-conversation support for Guanlan AI chat.
CREATE TABLE IF NOT EXISTS ai_conversations (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT NOT NULL,
    title      VARCHAR(100) NOT NULL DEFAULT '新对话',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ADD COLUMN IF NOT EXISTS is not supported in MySQL 8.0; use a stored procedure guard.
SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 'investory' AND TABLE_NAME = 'ai_chat_history' AND COLUMN_NAME = 'conversation_id');
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE ai_chat_history ADD COLUMN conversation_id BIGINT NULL AFTER user_id, ADD INDEX idx_conv (user_id, conversation_id)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
