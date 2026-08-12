-- NForce OneHR — Flyway Migration V105
-- (originally authored as V100, renumbered — see V103's header for why)
-- Closes the "configured but not executed" gap: ConfiguredAttendancePolicyEngine now copies the
-- matched rule's configured deduction amount onto PolicyDecision, and
-- AttendancePenaltyEvaluationService persists it here — a real execution path for Penalization
-- Policy's deduction fields, not stored-but-unused configuration. Additive/nullable only; no
-- existing column changes, no data migration (penalties evaluated before this column existed,
-- of which there are none in production, would simply have a null value).

ALTER TABLE attendance_penalties
    ADD COLUMN IF NOT EXISTS deduction_days NUMERIC(4,2);
