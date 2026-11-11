-- NForce OneHR — Flyway Migration V181
-- Safe follow-up to V180. Only reset the sequence when the exact 58+1 target dataset
-- is present and fully migrated. Fresh/unrelated databases are an intentional no-op.
-- Keep this separate from V180 because PostgreSQL setval() is not rolled back.

DO $$
DECLARE
    v_target_user_count INTEGER;
    v_renumbered_count INTEGER;
    v_special_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO v_target_user_count
    FROM employees
    WHERE user_id = ANY (ARRAY[
        '3da0aee3-805c-4df9-bd09-1f0661bb78a1'::uuid,
        '8d025675-3e20-4990-875d-f428e13b7287'::uuid,
        'b8cd45cc-cda3-4523-9c9d-86c1ab04204a'::uuid,
        '9ab9b23b-79ae-4f0d-bfd7-457930053e53'::uuid,
        'a78c2ded-ef52-4b4e-8062-0f69fded0890'::uuid,
        'a5de9f94-5fbd-43dd-9227-baabb5d7b2d3'::uuid,
        '44aedac0-d53b-4219-8e4b-e167d5dfc648'::uuid,
        '21a7f899-c07e-452f-8387-a511a11e0aeb'::uuid,
        'd859ad2b-bf96-4ff2-b59f-f39f7022f265'::uuid,
        'a5c24061-28e3-4d45-bac4-16cf9ec90337'::uuid,
        '7a48a2a5-82d1-4375-8d81-c6a06501c7ae'::uuid,
        '87974eee-a852-4ef5-8216-bd9688b5154f'::uuid,
        'dac299c9-0d04-4de4-8e59-6d0edd67bde7'::uuid,
        'fcf3ac72-d2eb-48e6-8804-c5166ae481fe'::uuid,
        '0689ef16-8b01-481d-8c7c-21f269f46c9a'::uuid,
        'f0755426-6a2e-4452-8316-994946748c0c'::uuid,
        'aa47d255-f492-4a99-a450-3877ea7ac09e'::uuid,
        'ccdec0d0-733d-4db4-97f0-e83c8beda7de'::uuid,
        '69c3a168-b4a1-473c-b54e-1f95c9906c1d'::uuid,
        '363023d8-7872-472f-8c3c-22e049fddc7c'::uuid,
        'd97cfa92-c34e-4dc6-b2f7-d385abebdc4e'::uuid,
        '3b517818-a516-4899-b2c2-588e3f22f1e9'::uuid,
        '08a985c1-3983-402d-80cb-f7d0e139f29f'::uuid,
        '4befe15b-6072-485f-91d4-0f3c508dd185'::uuid,
        'b1b6ae27-0bf9-48a1-9fde-5ca12734a405'::uuid,
        'd144f8c8-31ca-4b45-9417-c5cee47995c0'::uuid,
        '7b221c63-b530-46eb-ae98-ffc16227c13b'::uuid,
        'a8691ea1-a80f-4d56-9961-52159751a01b'::uuid,
        'dca7f157-8706-45f9-9261-c1a18fc45d35'::uuid,
        '68d2c68f-3da9-4c91-afd6-ae6eb41e5fc5'::uuid,
        'b2847702-4084-495b-92a4-ed4a3e953a08'::uuid,
        '1811f1d0-2173-4933-9dde-5bb96eb85c62'::uuid,
        'b43d9f32-619d-481a-9abc-7f2fc6d99f95'::uuid,
        '1e2f203a-9340-44f8-a1e2-3d18fcac4438'::uuid,
        '03723b88-929d-4ac9-81b0-66c3f4e218b0'::uuid,
        'fb1ad0d3-34c0-420c-a16f-395662067b9e'::uuid,
        '3a695522-219f-4a5f-bdc0-bf0c5278b483'::uuid,
        '0c7605a7-cda7-4454-a509-a923e8437bdc'::uuid,
        '5869bedb-3f94-4380-adea-a07ff21cbd11'::uuid,
        '5358dbad-159a-441f-a235-9fe70b53f5e9'::uuid,
        '386ffcae-8e8e-4e96-a8af-50212b7311f7'::uuid,
        'c30fdbdb-3417-4415-a78b-3cf80a9b910c'::uuid,
        '68a2e4b4-c558-4219-a133-454ee4a9e5e6'::uuid,
        '8dbcc5b2-b140-4490-846c-8169e8c04cda'::uuid,
        '68bbed90-59f9-49ce-9199-37a08157d0f8'::uuid,
        'f60d154d-1a7b-4bb2-8c64-9cef692ff125'::uuid,
        '8d6ac81a-b98f-42cd-9342-31893fb80c3f'::uuid,
        '622eff2f-5820-427c-bfed-27ca370496bb'::uuid,
        '4d43a443-d443-4c2b-9a42-e49f270a1688'::uuid,
        '2f59d3f7-03c6-4ccc-abf4-fbd26ab3440b'::uuid,
        '7a11a379-c0d0-4a69-8696-fd8d67e2a926'::uuid,
        'e7b6557a-9366-4d51-ba4d-46db93f56a73'::uuid,
        '08cedd7b-f13a-47d9-8a8c-cda931d6938a'::uuid,
        '6c02aa00-50bb-4a61-ad60-5661b4123281'::uuid,
        '50cdf137-142a-4f20-a191-6015ed36b758'::uuid,
        'f24ce3cd-af78-4ebb-85c3-cfd0a126a232'::uuid,
        '4f91f10c-0849-47e1-b4f2-531112fb942e'::uuid,
        '292830a9-db83-4f9c-bcaa-80520ebb79f6'::uuid
    ]::uuid[])
       OR user_id = '89274444-5f3e-444b-89b8-fd17cb3c57eb'::uuid;

    IF v_target_user_count = 0 THEN
        RAISE NOTICE 'V181: none of the 59 targeted employees exist; skipping sequence reset safely.';
        RETURN;
    END IF;

    WITH mapping(expected_position, user_id) AS (
        VALUES
        (1, '3da0aee3-805c-4df9-bd09-1f0661bb78a1'::uuid),
        (2, '8d025675-3e20-4990-875d-f428e13b7287'::uuid),
        (3, 'b8cd45cc-cda3-4523-9c9d-86c1ab04204a'::uuid),
        (4, '9ab9b23b-79ae-4f0d-bfd7-457930053e53'::uuid),
        (5, 'a78c2ded-ef52-4b4e-8062-0f69fded0890'::uuid),
        (6, 'a5de9f94-5fbd-43dd-9227-baabb5d7b2d3'::uuid),
        (7, '44aedac0-d53b-4219-8e4b-e167d5dfc648'::uuid),
        (8, '21a7f899-c07e-452f-8387-a511a11e0aeb'::uuid),
        (9, 'd859ad2b-bf96-4ff2-b59f-f39f7022f265'::uuid),
        (10, 'a5c24061-28e3-4d45-bac4-16cf9ec90337'::uuid),
        (11, '7a48a2a5-82d1-4375-8d81-c6a06501c7ae'::uuid),
        (12, '87974eee-a852-4ef5-8216-bd9688b5154f'::uuid),
        (13, 'dac299c9-0d04-4de4-8e59-6d0edd67bde7'::uuid),
        (14, 'fcf3ac72-d2eb-48e6-8804-c5166ae481fe'::uuid),
        (15, '0689ef16-8b01-481d-8c7c-21f269f46c9a'::uuid),
        (16, 'f0755426-6a2e-4452-8316-994946748c0c'::uuid),
        (17, 'aa47d255-f492-4a99-a450-3877ea7ac09e'::uuid),
        (18, 'ccdec0d0-733d-4db4-97f0-e83c8beda7de'::uuid),
        (19, '69c3a168-b4a1-473c-b54e-1f95c9906c1d'::uuid),
        (20, '363023d8-7872-472f-8c3c-22e049fddc7c'::uuid),
        (21, 'd97cfa92-c34e-4dc6-b2f7-d385abebdc4e'::uuid),
        (22, '3b517818-a516-4899-b2c2-588e3f22f1e9'::uuid),
        (23, '08a985c1-3983-402d-80cb-f7d0e139f29f'::uuid),
        (24, '4befe15b-6072-485f-91d4-0f3c508dd185'::uuid),
        (25, 'b1b6ae27-0bf9-48a1-9fde-5ca12734a405'::uuid),
        (26, 'd144f8c8-31ca-4b45-9417-c5cee47995c0'::uuid),
        (27, '7b221c63-b530-46eb-ae98-ffc16227c13b'::uuid),
        (28, 'a8691ea1-a80f-4d56-9961-52159751a01b'::uuid),
        (29, 'dca7f157-8706-45f9-9261-c1a18fc45d35'::uuid),
        (30, '68d2c68f-3da9-4c91-afd6-ae6eb41e5fc5'::uuid),
        (31, 'b2847702-4084-495b-92a4-ed4a3e953a08'::uuid),
        (32, '1811f1d0-2173-4933-9dde-5bb96eb85c62'::uuid),
        (33, 'b43d9f32-619d-481a-9abc-7f2fc6d99f95'::uuid),
        (34, '1e2f203a-9340-44f8-a1e2-3d18fcac4438'::uuid),
        (35, '03723b88-929d-4ac9-81b0-66c3f4e218b0'::uuid),
        (36, 'fb1ad0d3-34c0-420c-a16f-395662067b9e'::uuid),
        (37, '3a695522-219f-4a5f-bdc0-bf0c5278b483'::uuid),
        (38, '0c7605a7-cda7-4454-a509-a923e8437bdc'::uuid),
        (39, '5869bedb-3f94-4380-adea-a07ff21cbd11'::uuid),
        (40, '5358dbad-159a-441f-a235-9fe70b53f5e9'::uuid),
        (41, '386ffcae-8e8e-4e96-a8af-50212b7311f7'::uuid),
        (42, 'c30fdbdb-3417-4415-a78b-3cf80a9b910c'::uuid),
        (43, '68a2e4b4-c558-4219-a133-454ee4a9e5e6'::uuid),
        (44, '8dbcc5b2-b140-4490-846c-8169e8c04cda'::uuid),
        (45, '68bbed90-59f9-49ce-9199-37a08157d0f8'::uuid),
        (46, 'f60d154d-1a7b-4bb2-8c64-9cef692ff125'::uuid),
        (47, '8d6ac81a-b98f-42cd-9342-31893fb80c3f'::uuid),
        (48, '622eff2f-5820-427c-bfed-27ca370496bb'::uuid),
        (49, '4d43a443-d443-4c2b-9a42-e49f270a1688'::uuid),
        (50, '2f59d3f7-03c6-4ccc-abf4-fbd26ab3440b'::uuid),
        (51, '7a11a379-c0d0-4a69-8696-fd8d67e2a926'::uuid),
        (52, 'e7b6557a-9366-4d51-ba4d-46db93f56a73'::uuid),
        (53, '08cedd7b-f13a-47d9-8a8c-cda931d6938a'::uuid),
        (54, '6c02aa00-50bb-4a61-ad60-5661b4123281'::uuid),
        (55, '50cdf137-142a-4f20-a191-6015ed36b758'::uuid),
        (56, 'f24ce3cd-af78-4ebb-85c3-cfd0a126a232'::uuid),
        (57, '4f91f10c-0849-47e1-b4f2-531112fb942e'::uuid),
        (58, '292830a9-db83-4f9c-bcaa-80520ebb79f6'::uuid)
    )
    SELECT COUNT(*) INTO v_renumbered_count
    FROM employees e
    JOIN mapping m ON m.user_id = e.user_id
    WHERE e.employee_code = 'NF-2026' || (1000 + m.expected_position)::text;

    SELECT COUNT(*) INTO v_special_count
    FROM employees
    WHERE user_id = '89274444-5f3e-444b-89b8-fd17cb3c57eb'::uuid
      AND employee_code = 'NF-00334';

    IF v_target_user_count <> 59 OR v_renumbered_count <> 58 OR v_special_count <> 1 THEN
        RAISE EXCEPTION
            'V181 abort: expected the complete migrated target dataset (59 target rows, 58 renumbered employees, 1 special employee); found %, %, %. Refusing to reset employee_code_seq.',
            v_target_user_count, v_renumbered_count, v_special_count;
    END IF;

    PERFORM setval('employee_code_seq', 1058, true);

    RAISE NOTICE 'V181: employee_code_seq reset to 1058 (is_called=true); next generated Employee ID should be NF-20261059.';
END $$;
