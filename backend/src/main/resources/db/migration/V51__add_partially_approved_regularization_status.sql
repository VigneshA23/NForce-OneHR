-- NForce OneHR — Flyway Migration V51
-- Numbered V51 not V48 — V48 was already claimed on the shared dev DB by another branch's
-- unrelated migration ("add audit log occurred at index") by the time this was written.
-- Attendance Regularization: two-stage approval (Manager, then HR/Super Admin).
-- Introduces PARTIALLY_APPROVED as an intermediate status between PENDING and APPROVED.
--
-- The original CHECK on `status` (V17) was an unnamed inline constraint, so Postgres
-- auto-generated its name — looked up dynamically here rather than hardcoded, in case the
-- actual name differs from the conventional `<table>_<column>_check` pattern.
DO $$
DECLARE
    cname text;
BEGIN
    SELECT con.conname INTO cname
    FROM pg_constraint con
    JOIN pg_class rel ON rel.oid = con.conrelid
    JOIN pg_attribute att ON att.attrelid = rel.oid AND att.attnum = ANY(con.conkey)
    WHERE rel.relname = 'regularization_requests'
      AND con.contype = 'c'
      AND att.attname = 'status';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE regularization_requests DROP CONSTRAINT %I', cname);
    END IF;
END $$;

ALTER TABLE regularization_requests
    ADD CONSTRAINT regularization_requests_status_check
    CHECK (status IN ('PENDING', 'PARTIALLY_APPROVED', 'APPROVED', 'REJECTED'));

-- Mirrors idx_regularization_one_pending_per_date (V17) / idx_regularization_one_approved_per_date
-- (V47): a request already mid-review (partially approved) also blocks a fresh duplicate
-- submission for that date — otherwise nothing would stop a second request while the first
-- is still awaiting final approval.
CREATE UNIQUE INDEX idx_regularization_one_partially_approved_per_date
    ON regularization_requests(employee_user_id, attendance_date)
    WHERE status = 'PARTIALLY_APPROVED';
