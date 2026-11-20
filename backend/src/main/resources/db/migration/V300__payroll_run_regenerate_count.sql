-- Bank-details ticket (Keka-walkthrough amendments) — tracks how many times a run has been
-- regenerated, so the Pay Register can print which attempt ("Draft N") Finance is looking at.
ALTER TABLE payroll_runs ADD COLUMN regenerate_count integer NOT NULL DEFAULT 0;
