-- Three independent user-owned slots. Legacy settings require context confirmation.
CREATE TABLE ai_model_profiles (
    user_id BIGINT NOT NULL,
    slot_id INT NOT NULL,
    provider VARCHAR(32) NOT NULL,
    model VARCHAR(255) NOT NULL,
    base_url VARCHAR(1024) NOT NULL,
    api_key TEXT NOT NULL,
    context_window INT NULL,
    PRIMARY KEY (user_id, slot_id),
    CONSTRAINT chk_ai_model_slot CHECK (slot_id BETWEEN 1 AND 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_model_selection (
    user_id BIGINT NOT NULL PRIMARY KEY,
    slot_id INT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO ai_model_profiles (user_id, slot_id, provider, model, base_url, api_key, context_window)
SELECT user_id, 1, provider, model, COALESCE(base_url, ''), COALESCE(api_key, ''), NULL
FROM ai_settings
WHERE model <> '' AND COALESCE(base_url, '') <> '';

INSERT INTO ai_model_selection (user_id, slot_id)
SELECT user_id, slot_id FROM ai_model_profiles;
