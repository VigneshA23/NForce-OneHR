-- NForce OneHR — Flyway Migration V180
-- Employee ID migration, step 1 of 2: renumber the 58 retained employees plus one
-- soft-deleted employee to their new NF-YYYYNNNN Employee IDs (see EmployeeCodeGenerator).
--
-- This corrects a poisoned employee_code_seq (see V132's seed comment) by moving the 58
-- retained employees off scattered legacy/malformed codes onto a clean, contiguous
-- NF-2026-1001..1058 block, and moves one specific soft-deleted employee off the
-- timestamp-like code (NF-20269099) that was poisoning the sequence's seed calculation
-- onto a stable legacy-style code (NF-00334) outside the generated-code numbering scheme
-- entirely. Step 2 (V181) resets employee_code_seq to 1058 so the next generated ID is
-- NF-20261059 — kept in a SEPARATE migration deliberately: setval() is non-transactional
-- in PostgreSQL (a rollback does not undo it), so it must never share a transaction with
-- these UPDATEs. Flyway's own guarantee — strict version ordering, stop on first failure —
-- is what keeps the two safely sequenced: V181 cannot run unless V180 is recorded as
-- successfully applied first.
--
-- This is a shared, actively-used dev database (see this project's application.yml Flyway
-- comment) — other branches may be creating/modifying employee rows concurrently. Every
-- precondition below is therefore re-verified at migration time, not assumed from the
-- earlier read-only preflight: any mismatch aborts the whole migration with a clear error
-- (same fail-clearly-not-silently convention as V160) rather than partially renumbering.
--
-- Scope: employees.employee_code only. Does not touch user_id, full_name, deleted_at,
-- or any other table.
DO $$
DECLARE
    v_duplicate_count      INTEGER;
    v_fk_count             INTEGER;
    v_col_type             TEXT;
    v_col_maxlen           INTEGER;
    v_col_nullable         TEXT;
    v_unique_count         INTEGER;
    v_target_collisions    INTEGER;
    v_updated_58_count     INTEGER;
    v_updated_1_count      INTEGER;
    v_special_code         TEXT;
    v_special_deleted_at   TIMESTAMPTZ;
BEGIN
    -- ── Precondition: no unexpected employee_code duplicates exist yet ──────────────
    SELECT COUNT(*) INTO v_duplicate_count
    FROM (SELECT employee_code FROM employees GROUP BY employee_code HAVING COUNT(*) > 1) d;
    IF v_duplicate_count > 0 THEN
        RAISE EXCEPTION 'V180 abort: % duplicate employee_code value(s) already exist. Migration requires a clean starting state.', v_duplicate_count;
    END IF;

    -- ── Precondition: employee_code column/constraint shape matches assumptions ─────
    SELECT data_type, character_maximum_length, is_nullable
      INTO v_col_type, v_col_maxlen, v_col_nullable
    FROM information_schema.columns
    WHERE table_name = 'employees' AND column_name = 'employee_code';

    IF v_col_type IS DISTINCT FROM 'character varying'
       OR v_col_maxlen IS DISTINCT FROM 20
       OR v_col_nullable IS DISTINCT FROM 'NO' THEN
        RAISE EXCEPTION 'V180 abort: employees.employee_code no longer matches expected shape (VARCHAR(20) NOT NULL) — found type=%, max_length=%, nullable=%. Schema has drifted from this migration''s assumptions.',
            v_col_type, v_col_maxlen, v_col_nullable;
    END IF;

    SELECT COUNT(*) INTO v_unique_count
    FROM information_schema.table_constraints tc
    JOIN information_schema.key_column_usage kcu
        ON kcu.constraint_name = tc.constraint_name AND kcu.table_schema = tc.table_schema
    WHERE tc.table_name = 'employees'
      AND kcu.column_name = 'employee_code'
      AND tc.constraint_type = 'UNIQUE';
    IF v_unique_count = 0 THEN
        RAISE EXCEPTION 'V180 abort: no UNIQUE constraint found on employees.employee_code. Refusing to proceed without the uniqueness guarantee this migration relies on.';
    END IF;

    -- ── Precondition: nothing has a foreign key referencing employees.employee_code ─
    SELECT COUNT(*) INTO v_fk_count
    FROM information_schema.table_constraints tc
    JOIN information_schema.key_column_usage kcu
        ON kcu.constraint_name = tc.constraint_name AND kcu.table_schema = tc.table_schema
    JOIN information_schema.constraint_column_usage ccu
        ON ccu.constraint_name = tc.constraint_name AND ccu.table_schema = tc.table_schema
    WHERE tc.constraint_type = 'FOREIGN KEY'
      AND ccu.table_name = 'employees'
      AND ccu.column_name = 'employee_code';
    IF v_fk_count > 0 THEN
        RAISE EXCEPTION 'V180 abort: % foreign key(s) reference employees.employee_code directly. Renumbering would be unsafe.', v_fk_count;
    END IF;

    -- ── Precondition: none of the 59 target codes are already occupied ──────────────
    SELECT COUNT(*) INTO v_target_collisions
    FROM employees e
    WHERE e.employee_code = 'NF-00334'
       OR e.employee_code IN (
            SELECT 'NF-2026' || (1000 + gs)::text FROM generate_series(1, 58) AS gs
       );
    IF v_target_collisions <> 0 THEN
        RAISE EXCEPTION 'V180 abort: % of the 59 target employee_code values are already occupied.', v_target_collisions;
    END IF;

    -- ── Precondition: the special soft-deleted employee is exactly as expected ──────
    SELECT e.employee_code, u.deleted_at
      INTO v_special_code, v_special_deleted_at
    FROM employees e
    JOIN users u ON u.id = e.user_id
    WHERE e.user_id = '89274444-5f3e-444b-89b8-fd17cb3c57eb';

    IF v_special_code IS DISTINCT FROM 'NF-20269099' THEN
        RAISE EXCEPTION 'V180 abort: expected employee_code NF-20269099 for user_id 89274444-5f3e-444b-89b8-fd17cb3c57eb, found % (or row missing).', COALESCE(v_special_code, '<no row>');
    END IF;
    IF v_special_deleted_at IS NULL THEN
        RAISE EXCEPTION 'V180 abort: user_id 89274444-5f3e-444b-89b8-fd17cb3c57eb is expected to be soft-deleted (users.deleted_at IS NOT NULL) but is not. Refusing to renumber — this row may not be the employee this migration expects.';
    END IF;

    -- ── Update 1 of 2: the 58 retained employees. WHERE requires an exact
    -- (user_id, current employee_code) match — any row that has drifted since the
    -- mapping was established simply won't match, and the row-count check below
    -- aborts the whole migration rather than silently updating a subset. ────────────
    WITH mapping(expected_position, user_id, old_code) AS (
        VALUES
            (1,  '3da0aee3-805c-4df9-bd09-1f0661bb78a1'::uuid, 'NF-0001'),
            (2,  '8d025675-3e20-4990-875d-f428e13b7287'::uuid, 'NF-1234'),
            (3,  'b8cd45cc-cda3-4523-9c9d-86c1ab04204a'::uuid, 'NF-677887'),
            (4,  '9ab9b23b-79ae-4f0d-bfd7-457930053e53'::uuid, 'NF-7878876'),
            (5,  'a78c2ded-ef52-4b4e-8062-0f69fded0890'::uuid, 'NF-0005'),
            (6,  'a5de9f94-5fbd-43dd-9227-baabb5d7b2d3'::uuid, 'NF-7878895'),
            (7,  '44aedac0-d53b-4219-8e4b-e167d5dfc648'::uuid, 'NF-007'),
            (8,  '21a7f899-c07e-452f-8387-a511a11e0aeb'::uuid, 'NF-7878916'),
            (9,  'd859ad2b-bf96-4ff2-b59f-f39f7022f265'::uuid, 'NF-7878934'),
            (10, 'a5c24061-28e3-4d45-bac4-16cf9ec90337'::uuid, 'NF-7878941'),
            (11, '7a48a2a5-82d1-4375-8d81-c6a06501c7ae'::uuid, 'NF-7878947'),
            (12, '87974eee-a852-4ef5-8216-bd9688b5154f'::uuid, 'NF-7878971'),
            (13, 'dac299c9-0d04-4de4-8e59-6d0edd67bde7'::uuid, 'NF-7878974'),
            (14, 'fcf3ac72-d2eb-48e6-8804-c5166ae481fe'::uuid, 'NF-7878978'),
            (15, '0689ef16-8b01-481d-8c7c-21f269f46c9a'::uuid, 'NF-7878995'),
            (16, 'f0755426-6a2e-4452-8316-994946748c0c'::uuid, 'NF-7879002'),
            (17, 'aa47d255-f492-4a99-a450-3877ea7ac09e'::uuid, 'NF-7879040'),
            (18, 'ccdec0d0-733d-4db4-97f0-e83c8beda7de'::uuid, 'NF-2026-0004'),
            (19, '69c3a168-b4a1-473c-b54e-1f95c9906c1d'::uuid, 'NF-2026-0005'),
            (20, '363023d8-7872-472f-8c3c-22e049fddc7c'::uuid, 'NF-2026-0017'),
            (21, 'd97cfa92-c34e-4dc6-b2f7-d385abebdc4e'::uuid, 'NF-20260026'),
            (22, '3b517818-a516-4899-b2c2-588e3f22f1e9'::uuid, 'NF-20260034'),
            (23, '08a985c1-3983-402d-80cb-f7d0e139f29f'::uuid, 'NF-20260041'),
            (24, '4befe15b-6072-485f-91d4-0f3c508dd185'::uuid, 'NF-20260043'),
            (25, 'b1b6ae27-0bf9-48a1-9fde-5ca12734a405'::uuid, 'NF-20260080'),
            (26, 'd144f8c8-31ca-4b45-9417-c5cee47995c0'::uuid, 'NF-20260098'),
            (27, '7b221c63-b530-46eb-ae98-ffc16227c13b'::uuid, 'NF-20260102'),
            (28, 'a8691ea1-a80f-4d56-9961-52159751a01b'::uuid, 'NF-20260103'),
            (29, 'dca7f157-8706-45f9-9261-c1a18fc45d35'::uuid, 'NF-20260104'),
            (30, '68d2c68f-3da9-4c91-afd6-ae6eb41e5fc5'::uuid, 'NF-20260105'),
            (31, 'b2847702-4084-495b-92a4-ed4a3e953a08'::uuid, 'NF-20260107'),
            (32, '1811f1d0-2173-4933-9dde-5bb96eb85c62'::uuid, 'NF-20260146'),
            (33, 'b43d9f32-619d-481a-9abc-7f2fc6d99f95'::uuid, 'NF-20260147'),
            (34, '1e2f203a-9340-44f8-a1e2-3d18fcac4438'::uuid, 'NF-20260148'),
            (35, '03723b88-929d-4ac9-81b0-66c3f4e218b0'::uuid, 'NF-20260149'),
            (36, 'fb1ad0d3-34c0-420c-a16f-395662067b9e'::uuid, 'NF-20260150'),
            (37, '3a695522-219f-4a5f-bdc0-bf0c5278b483'::uuid, 'NF-20260151'),
            (38, '0c7605a7-cda7-4454-a509-a923e8437bdc'::uuid, 'NF-20260152'),
            (39, '5869bedb-3f94-4380-adea-a07ff21cbd11'::uuid, 'NF-20260153'),
            (40, '5358dbad-159a-441f-a235-9fe70b53f5e9'::uuid, 'NF-20260154'),
            (41, '386ffcae-8e8e-4e96-a8af-50212b7311f7'::uuid, 'NF-20260155'),
            (42, 'c30fdbdb-3417-4415-a78b-3cf80a9b910c'::uuid, 'NF-20260158'),
            (43, '68a2e4b4-c558-4219-a133-454ee4a9e5e6'::uuid, 'NF-20260162'),
            (44, '8dbcc5b2-b140-4490-846c-8169e8c04cda'::uuid, 'NF-20260163'),
            (45, '68bbed90-59f9-49ce-9199-37a08157d0f8'::uuid, 'NF-20260164'),
            (46, 'f60d154d-1a7b-4bb2-8c64-9cef692ff125'::uuid, 'NF-20260165'),
            (47, '8d6ac81a-b98f-42cd-9342-31893fb80c3f'::uuid, 'NF-20260166'),
            (48, '622eff2f-5820-427c-bfed-27ca370496bb'::uuid, 'NF-20260167'),
            (49, '4d43a443-d443-4c2b-9a42-e49f270a1688'::uuid, 'NF-20260168'),
            (50, '2f59d3f7-03c6-4ccc-abf4-fbd26ab3440b'::uuid, 'NF-20260169'),
            (51, '7a11a379-c0d0-4a69-8696-fd8d67e2a926'::uuid, 'NF-20260170'),
            (52, 'e7b6557a-9366-4d51-ba4d-46db93f56a73'::uuid, 'NF-20260171'),
            (53, '08cedd7b-f13a-47d9-8a8c-cda931d6938a'::uuid, 'NF-20260172'),
            (54, '6c02aa00-50bb-4a61-ad60-5661b4123281'::uuid, 'NF-20260173'),
            (55, '50cdf137-142a-4f20-a191-6015ed36b758'::uuid, 'NF-20260174'),
            (56, 'f24ce3cd-af78-4ebb-85c3-cfd0a126a232'::uuid, 'NF-20260175'),
            (57, '4f91f10c-0849-47e1-b4f2-531112fb942e'::uuid, 'NF-20260176'),
            (58, '292830a9-db83-4f9c-bcaa-80520ebb79f6'::uuid, 'NF-20260177')
    )
    UPDATE employees e
    SET employee_code = 'NF-2026' || (1000 + m.expected_position)::text
    FROM mapping m
    WHERE e.user_id = m.user_id
      AND e.employee_code = m.old_code;

    GET DIAGNOSTICS v_updated_58_count = ROW_COUNT;
    IF v_updated_58_count <> 58 THEN
        RAISE EXCEPTION 'V180 abort: expected exactly 58 retained-employee rows updated, got %. Live data has drifted from the established mapping — no changes committed.', v_updated_58_count;
    END IF;

    -- ── Update 2 of 2: the single soft-deleted employee ──────────────────────────────
    UPDATE employees
    SET employee_code = 'NF-00334'
    WHERE user_id = '89274444-5f3e-444b-89b8-fd17cb3c57eb'
      AND employee_code = 'NF-20269099';

    GET DIAGNOSTICS v_updated_1_count = ROW_COUNT;
    IF v_updated_1_count <> 1 THEN
        RAISE EXCEPTION 'V180 abort: expected exactly 1 row updated for the soft-deleted employee (NF-20269099 -> NF-00334), got %. No changes committed.', v_updated_1_count;
    END IF;

    -- ── Post-update assertion: still no duplicate employee_code values ──────────────
    SELECT COUNT(*) INTO v_duplicate_count
    FROM (SELECT employee_code FROM employees GROUP BY employee_code HAVING COUNT(*) > 1) d;
    IF v_duplicate_count > 0 THEN
        RAISE EXCEPTION 'V180 abort: % duplicate employee_code value(s) exist after the update. Aborting — this should be structurally impossible given the pre-flight checks above.', v_duplicate_count;
    END IF;

    RAISE NOTICE 'V180: employee ID renumbering complete — % retained employees + % soft-deleted employee = % rows updated. Sequence reset deferred to V181.',
        v_updated_58_count, v_updated_1_count, v_updated_58_count + v_updated_1_count;
END $$;
