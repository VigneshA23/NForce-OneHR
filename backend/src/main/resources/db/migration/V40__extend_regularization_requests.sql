-- NForce OneHR — Flyway Migration V40
-- Attendance Regularization: persist a single assigned approver on the request itself,
-- instead of computing "who may act on this" live from EmployeeManagerHistory on every
-- read (V17/RegularizationService). Resolved once at submission time: the employee's
-- selected manager, else their current manager, else left NULL (HR/Super Admin already
-- have blanket override visibility regardless of this column — see RegularizationService).
--
-- Numbered V40 (not V34) because another branch's V34__create_holidays_table landed on
-- the shared dev database first — see flyway_schema_history before assuming V34 is free.

ALTER TABLE regularization_requests
    ADD COLUMN IF NOT EXISTS assigned_approver_id UUID REFERENCES users(id);

CREATE INDEX IF NOT EXISTS idx_regularization_assigned_approver
    ON regularization_requests(assigned_approver_id);

-- Backfill existing PENDING rows from the employee's current manager so in-flight
-- requests aren't stranded with no assigned approver after this migration. Already
-- decided (APPROVED/REJECTED) rows are left NULL — that column is never read for them.
UPDATE regularization_requests r
SET    assigned_approver_id = h.manager_user_id
FROM   employee_manager_history h
WHERE  h.employee_user_id = r.employee_user_id
  AND  h.effective_to IS NULL
  AND  r.status = 'PENDING'
  AND  r.assigned_approver_id IS NULL;
