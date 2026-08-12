-- NForce OneHR — Flyway Migration V104
-- (originally authored as V99, renumbered — see V103's header for why)
-- Organization Masters: Penalization Policy. Net-new — the existing V95 penalisation_policies
-- table is an unrelated employee-assignment label (name/description only, see
-- EmployeeAssignmentService) and is left untouched by this migration.
--
-- One policy document, versioned as a whole (policy_id stable across versions, version an
-- incrementing integer — matching AttendancePenalty.policy_id/policy_version's existing
-- point-in-time-snapshot contract), with four independently enable-able sections. Explicit
-- typed columns rather than a JSON blob: the screenshot-confirmed field set is fully enumerated,
-- not open-ended. Single-option dropdowns visible in the approved screenshots (basis/period
-- selectors) are persisted as CHECK-constrained columns so the UI can still render them
-- faithfully, without inventing any additional option beyond the one confirmed value.
--
-- No seed data — an empty table is the correct "no policy configured" state; policy evaluation
-- (ConfiguredAttendancePolicyEngine) treats that as NO_MATCH, same as historically.

CREATE TABLE IF NOT EXISTS penalization_policy_versions (
    id                                          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Stable across every version of this org's one Penalization Policy document.
    policy_id                                   UUID         NOT NULL,
    version                                     INTEGER      NOT NULL,

    -- Computed at save time (see PenalizationPolicyService): the 1st of the calendar month
    -- after the save date — the only cycle boundary the approved screenshots demonstrate.
    -- effective_to is set on the row it supersedes when a newer version is saved.
    effective_from                              TIMESTAMPTZ  NOT NULL,
    effective_to                                TIMESTAMPTZ,

    -- ── No Attendance ──
    no_attendance_enabled                       BOOLEAN      NOT NULL DEFAULT false,
    na_deduction_days                           NUMERIC(4,2),
    na_no_show_enabled                          BOOLEAN      NOT NULL DEFAULT false,
    na_no_show_threshold_hours                  NUMERIC(5,2),

    -- ── Late Arrival ──
    late_arrival_enabled                        BOOLEAN      NOT NULL DEFAULT false,
    la_basis                                    VARCHAR(30)  CHECK (la_basis IN ('NUMBER_OF_INCIDENTS')),
    la_grace_period_minutes                     INTEGER,
    la_exempt_count                             INTEGER,
    la_exempt_period                            VARCHAR(20)  CHECK (la_exempt_period IN ('MONTH')),
    la_deduction_days                           NUMERIC(4,2),
    la_deduction_per_shifts                     INTEGER,
    la_ignore_when_effective_hours_met_enabled  BOOLEAN      NOT NULL DEFAULT false,

    -- ── Work Hours Shortage ──
    work_hours_shortage_enabled                 BOOLEAN      NOT NULL DEFAULT false,
    whs_deduction_basis                         VARCHAR(30)  CHECK (whs_deduction_basis IN ('EFFECTIVE_HOURS')),
    whs_deduction_period                        VARCHAR(20)  CHECK (whs_deduction_period IN ('DAY')),
    whs_apply_penalty_for_shortage_enabled      BOOLEAN      NOT NULL DEFAULT true,
    whs_apply_penalty_for_late_arrival_enabled  BOOLEAN      NOT NULL DEFAULT false,

    -- ── Missing Logs ──
    missing_logs_enabled                        BOOLEAN      NOT NULL DEFAULT false,
    ml_exempt_days                              INTEGER,
    ml_exempt_period                            VARCHAR(20)  CHECK (ml_exempt_period IN ('MONTH')),
    ml_deduction_mode                           VARCHAR(20)  CHECK (ml_deduction_mode IN ('PER_SHIFT', 'IRRESPECTIVE')),
    ml_deduction_days                           NUMERIC(4,2),
    ml_deduction_per_shifts                     INTEGER,
    ml_ignore_rule_enabled                      BOOLEAN      NOT NULL DEFAULT false,
    ml_ignore_rule_threshold_percent            NUMERIC(5,2),

    created_by                                  UUID         NOT NULL,
    created_at                                  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_penalization_policy_versions_policy_version
    ON penalization_policy_versions (policy_id, version);

-- One version is "current" per policy_id at a time — the row with effective_to IS NULL.
CREATE INDEX IF NOT EXISTS idx_penalization_policy_versions_current
    ON penalization_policy_versions (policy_id) WHERE effective_to IS NULL;

-- Work Hours Shortage's tiered deduction table (variable-length list; the approved screenshot
-- shows a delete icon per row, confirming rows are a genuine list — no "add row" control is
-- shown, so the edit UI only supports editing/removing existing tiers, not adding new ones).
CREATE TABLE IF NOT EXISTS penalization_policy_work_hours_tiers (
    id                    UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_version_id     UUID          NOT NULL REFERENCES penalization_policy_versions(id),
    threshold_percent     NUMERIC(5,2)  NOT NULL,
    deduction_days        NUMERIC(4,2)  NOT NULL,
    sort_order            INTEGER       NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_penalization_policy_work_hours_tiers_version
    ON penalization_policy_work_hours_tiers (policy_version_id);
