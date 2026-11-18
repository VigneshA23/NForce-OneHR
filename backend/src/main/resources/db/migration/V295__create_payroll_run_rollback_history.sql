-- A run's own last_rolled_back_at/last_rollback_reason/last_rolled_back_by columns only ever hold
-- the MOST RECENT rollback — a run submitted, approved and rolled back more than once overwrites
-- them each time. With more than one Super Admin able to roll a run back, HR needs the full list
-- of every rollback (who, when, why), not just the latest. Backfill one row per run that already
-- has rollback data so history isn't lost for runs rolled back before this table existed (actor is
-- unknown for those, same as today's "—" display).
CREATE TABLE payroll_run_rollbacks (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payroll_run_id uuid NOT NULL,
    reason text NOT NULL,
    rolled_back_by uuid,
    rolled_back_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_payroll_run_rollbacks_run ON payroll_run_rollbacks(payroll_run_id);

INSERT INTO payroll_run_rollbacks (payroll_run_id, reason, rolled_back_by, rolled_back_at)
SELECT id, COALESCE(last_rollback_reason, ''), last_rolled_back_by, last_rolled_back_at
FROM payroll_runs
WHERE last_rolled_back_at IS NOT NULL;
