CREATE TABLE IF NOT EXISTS user_topic_mastery (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    topic_id BIGINT NOT NULL REFERENCES practice_topics(id) ON DELETE CASCADE,
    mastery_probability DOUBLE PRECISION NOT NULL DEFAULT 0.20,
    prior_probability DOUBLE PRECISION NOT NULL DEFAULT 0.20,
    learn_probability DOUBLE PRECISION NOT NULL DEFAULT 0.12,
    guess_probability DOUBLE PRECISION NOT NULL DEFAULT 0.20,
    slip_probability DOUBLE PRECISION NOT NULL DEFAULT 0.10,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    correct_count INTEGER NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_user_topic_mastery UNIQUE (user_id, topic_id)
);
CREATE INDEX IF NOT EXISTS idx_topic_mastery_user ON user_topic_mastery(user_id);

CREATE TABLE IF NOT EXISTS user_review_schedule (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    question_id BIGINT NOT NULL REFERENCES practice_questions(id) ON DELETE CASCADE,
    repetitions INTEGER NOT NULL DEFAULT 0,
    interval_days INTEGER NOT NULL DEFAULT 0,
    ease_factor DOUBLE PRECISION NOT NULL DEFAULT 2.5,
    last_quality INTEGER NOT NULL DEFAULT 0,
    last_reviewed_at TIMESTAMPTZ,
    due_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_user_question_review UNIQUE (user_id, question_id)
);
CREATE INDEX IF NOT EXISTS idx_review_due ON user_review_schedule(user_id, due_at);

CREATE TABLE IF NOT EXISTS personalized_plans (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    subject_name VARCHAR(120) NOT NULL,
    summary VARCHAR(1200) NOT NULL,
    primary_weakness VARCHAR(500),
    engine VARCHAR(120),
    adherence_rate DOUBLE PRECISION NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_personalized_plan_user ON personalized_plans(user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS personalized_plan_days (
    id BIGSERIAL PRIMARY KEY,
    plan_id BIGINT NOT NULL REFERENCES personalized_plans(id) ON DELETE CASCADE,
    day_number INTEGER NOT NULL,
    target_date DATE NOT NULL,
    focus VARCHAR(500) NOT NULL,
    objective VARCHAR(1200),
    planned_minutes INTEGER NOT NULL,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_plan_day UNIQUE (plan_id, day_number)
);
CREATE INDEX IF NOT EXISTS idx_plan_day_target ON personalized_plan_days(target_date, completed);

CREATE TABLE IF NOT EXISTS personalized_plan_tasks (
    id BIGSERIAL PRIMARY KEY,
    plan_day_id BIGINT NOT NULL REFERENCES personalized_plan_days(id) ON DELETE CASCADE,
    topic_id BIGINT REFERENCES practice_topics(id) ON DELETE SET NULL,
    description VARCHAR(1000) NOT NULL,
    required_attempts INTEGER NOT NULL DEFAULT 1,
    completed_attempts INTEGER NOT NULL DEFAULT 0,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    completed_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_plan_task_day ON personalized_plan_tasks(plan_day_id, completed);
