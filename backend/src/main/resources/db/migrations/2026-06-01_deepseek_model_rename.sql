-- Migration: rename legacy DeepSeek model names to v4 series.
-- Context: deepseek-chat and deepseek-reasoner are being deprecated by DeepSeek.
-- Mapping: deepseek-chat → deepseek-v4-flash, deepseek-reasoner → deepseek-v4-pro.
-- Date: 2026-06-01
--
-- Idempotent: re-running has no effect once values are migrated.

UPDATE ai_settings
SET model = 'deepseek-v4-flash'
WHERE provider = 'deepseek' AND model = 'deepseek-chat';

UPDATE ai_settings
SET model = 'deepseek-v4-pro'
WHERE provider = 'deepseek' AND model = 'deepseek-reasoner';

-- Also handle users who picked the 'custom' provider but pointed at api.deepseek.com.
UPDATE ai_settings
SET model = 'deepseek-v4-flash'
WHERE base_url LIKE '%deepseek%' AND model = 'deepseek-chat';

UPDATE ai_settings
SET model = 'deepseek-v4-pro'
WHERE base_url LIKE '%deepseek%' AND model = 'deepseek-reasoner';
