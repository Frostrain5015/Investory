-- Phase 3: vector memory for Guanlan AI agent.
-- Replaces the old ai_chat_history role='memory' key-value store with
-- content + embedding (JSON array of floats stored as LONGTEXT — MySQL 8.0
-- lacks native VECTOR, but per-user ≤50 embeddings × ~6KB each is manageable
-- with Python-side brute-force cosine).
CREATE TABLE IF NOT EXISTS ai_memory (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT NOT NULL,
    content    VARCHAR(2000) NOT NULL,
    embedding  LONGTEXT,                -- JSON array of float32, e.g. "[0.12,-0.34,...]"
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Populate from old flat memories (one-time migration). Old rows are
-- embedded lazily by the new tool_remember when they are first retrieved
-- (if embedding is NULL), so we only insert the content now.
INSERT IGNORE INTO ai_memory (user_id, content)
    SELECT user_id, content FROM ai_chat_history WHERE role = 'memory';
