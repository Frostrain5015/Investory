ALTER TABLE users ADD COLUMN frost_id_id VARCHAR(256) DEFAULT NULL AFTER email;
CREATE INDEX idx_users_frost_id_id ON users (frost_id_id);
