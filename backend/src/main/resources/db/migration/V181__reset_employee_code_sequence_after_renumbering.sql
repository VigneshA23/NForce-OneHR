-- NForce OneHR — Flyway Migration V181
-- Employee ID migration, step 2 of 2: reset employee_code_seq now that V180 has moved the
-- 58 retained employees onto the contiguous NF-2026-1001..1058 block (see V180 for the
-- update itself and why this is a separate migration — setval() is non-transactional in
-- PostgreSQL, so it must never share a transaction with the renumbering UPDATEs).
--
-- Flyway will not reach this migration unless V180 already committed successfully — so if
-- V180 aborted for any reason, this file simply never executes and the sequence is
-- untouched, with no further guard needed for that specific failure mode. The check below
-- guards a different, narrower risk: something else changing the relevant employee rows in
-- the (necessarily brief, but non-zero on a shared multi-branch dev database) window
-- between V180 committing and V181 running.
--
-- Effect: employee_code_seq.last_value = 1058, is_called = true, so the next nextval()
-- anywhere in the application returns 1059, formatting as NF-20261059 (see
-- EmployeeCodeGenerator#format). Does not call nextval() itself — that would consume a
-- production sequence value for no reason.
DO $$
DECLARE
    v_renumbered_count INTEGER;
    v_special_code      TEXT;
BEGIN
    SELECT COUNT(*) INTO v_renumbered_count
    FROM employees
    WHERE employee_code IN (SELECT 'NF-2026' || (1000 + gs)::text FROM generate_series(1, 58) AS gs);

    IF v_renumbered_count <> 58 THEN
        RAISE EXCEPTION 'V181 abort: expected exactly 58 employees on the NF-20261001..NF-20261058 block, found %. Refusing to reset employee_code_seq — investigate before retrying.', v_renumbered_count;
    END IF;

    SELECT employee_code INTO v_special_code
    FROM employees
    WHERE user_id = '89274444-5f3e-444b-89b8-fd17cb3c57eb';

    IF v_special_code IS DISTINCT FROM 'NF-00334' THEN
        RAISE EXCEPTION 'V181 abort: expected employee_code NF-00334 for user_id 89274444-5f3e-444b-89b8-fd17cb3c57eb, found %. Refusing to reset employee_code_seq.', COALESCE(v_special_code, '<no row>');
    END IF;

    PERFORM setval('employee_code_seq', 1058, true);

    RAISE NOTICE 'V181: employee_code_seq reset to 1058 (is_called=true) — next generated Employee ID is NF-20261059.';
END $$;
