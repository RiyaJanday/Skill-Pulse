-- Optimistic locking for mastery rows: a double-click or two parallel submits can no longer silently
-- overwrite each other; the loser gets an optimistic-lock error and the service retries once.
ALTER TABLE user_topic_mastery ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
