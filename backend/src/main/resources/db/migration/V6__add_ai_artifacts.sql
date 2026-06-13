CREATE TABLE IF NOT EXISTS ai_artifacts (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  conversation_id BIGINT NULL,
  type VARCHAR(64) NOT NULL,
  title VARCHAR(200) NOT NULL,
  summary TEXT NULL,
  content_json JSON NULL,
  content_markdown MEDIUMTEXT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_ai_artifacts_conversation (user_id, conversation_id, created_at),
  INDEX idx_ai_artifacts_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
