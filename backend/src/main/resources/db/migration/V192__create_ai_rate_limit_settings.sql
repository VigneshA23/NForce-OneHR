-- Persisted, Super-Admin-editable AI Assistant rate-limit budget. Singleton row, same
-- UNIQUE/CHECK(singleton) enforcement pattern as attendance_rules (V162): the app never toggles
-- `singleton`, it exists purely so a second row is impossible at the DB level.
--
-- Numbered V192, not V190: on the shared dev Neon DB, V190 ("employee document versioning") and
-- V191 ("policy versioning") were already applied directly by other branches without their files
-- ever being committed here (the same pattern application-local.yml already documents for V30+).
-- validate-on-migrate: false let this file's original V190 name sit unapplied and unnoticed since
-- Flyway saw version 190 already recorded as done and silently skipped it - the actual bug this
-- rename fixes. Confirmed 191 as the live max via flyway_schema_history before choosing 192.
--
-- Seeded to match the previous static default (app.ai.limits.max-requests-per-user-per-hour: 60,
-- an implicit 1-hour window) so deploying this migration changes no behaviour until a Super Admin
-- actually changes the setting.
CREATE TABLE ai_rate_limit_settings (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    singleton           BOOLEAN NOT NULL DEFAULT TRUE,
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    requests_per_window INTEGER NOT NULL DEFAULT 60,
    window_minutes      INTEGER NOT NULL DEFAULT 60,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_ai_rate_limit_singleton CHECK (singleton),
    CONSTRAINT uq_ai_rate_limit_singleton UNIQUE (singleton),
    CONSTRAINT chk_ai_rate_limit_requests CHECK (requests_per_window > 0 AND requests_per_window <= 1000),
    CONSTRAINT chk_ai_rate_limit_window CHECK (window_minutes > 0 AND window_minutes <= 1440)
);

INSERT INTO ai_rate_limit_settings (enabled, requests_per_window, window_minutes)
VALUES (TRUE, 60, 60)
ON CONFLICT (singleton) DO NOTHING;
