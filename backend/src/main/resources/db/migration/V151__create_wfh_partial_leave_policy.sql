-- NForce OneHR — Flyway Migration V151
-- Numbered V151 (not V143) because the shared dev DB's schema_version was already at 150 by
-- the time this was written — this branch's own migration files only go up to V143, so it's
-- missing whatever added V144-V150 elsewhere (likely `dev` has moved ahead of this branch).
-- V151 is the actual next-free version against that DB right now; renumber again if a merge
-- brings in migrations that land on 151 first.
--
-- Makes the WFH monthly-days limit and Partial Day monthly-minutes limit Super Admin
-- configurable, replacing the previous hardcoded constants in AttendanceRequestService
-- (WFH_MONTHLY_LIMIT_DAYS = 2, PARTIAL_DAY_MONTHLY_LIMIT_MINUTES = 120). A single-row table
-- (enforced by the id = 1 check) rather than an application.properties value, specifically so
-- Super Admin can change it from the UI and have it take effect immediately — no redeploy.

CREATE TABLE wfh_partial_leave_policy (
    id                                  SMALLINT     PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    wfh_monthly_limit_days              INTEGER      NOT NULL DEFAULT 2 CHECK (wfh_monthly_limit_days >= 0),
    partial_leave_monthly_limit_minutes INTEGER      NOT NULL DEFAULT 120 CHECK (partial_leave_monthly_limit_minutes >= 0),
    updated_by                          UUID         REFERENCES users(id),
    updated_at                          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Seed the single row with the exact values that were previously hardcoded, so existing
-- employees' monthly allowances don't change the moment this migration runs.
INSERT INTO wfh_partial_leave_policy (id, wfh_monthly_limit_days, partial_leave_monthly_limit_minutes)
VALUES (1, 2, 120);
