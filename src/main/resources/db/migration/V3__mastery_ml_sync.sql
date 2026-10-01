-- Marks mastery values that were written while the Python ML service was unavailable.
-- ml_synced = FALSE means "attempts were counted but BKT was not applied"; the value is
-- recomputed from the stored attempts (POST /bkt/replay) as soon as the service is back.
ALTER TABLE user_topic_mastery ADD COLUMN IF NOT EXISTS ml_synced BOOLEAN NOT NULL DEFAULT TRUE;
