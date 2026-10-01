-- Base tables that the later migrations point at.
--
-- Hibernate (ddl-auto=update) owns the base schema, but Flyway runs BEFORE Hibernate. On a brand-new database
-- (Docker, Railway) V2 would fail because app_users, practice_topics and practice_questions did not exist yet.
-- These stubs only hold the primary key; Hibernate adds every other column and constraint right after Flyway.
--
-- Existing databases are unaffected: they were baselined at version 1 (spring.flyway.baseline-version=1), so
-- Flyway never runs this file there.
CREATE TABLE IF NOT EXISTS app_users (id BIGSERIAL PRIMARY KEY);
CREATE TABLE IF NOT EXISTS practice_topics (id BIGSERIAL PRIMARY KEY);
CREATE TABLE IF NOT EXISTS practice_questions (id BIGSERIAL PRIMARY KEY);
