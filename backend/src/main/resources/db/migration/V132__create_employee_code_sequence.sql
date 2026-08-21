-- NForce OneHR — Flyway Migration V131
-- Global, monotonically increasing sequence backing the new NF-YYYY-XXXX Employee ID format
-- (see EmployeeCodeGenerator). The sequence only numbers the ID's numeric suffix — it is
-- deliberately global and never reset by calendar year; YYYY in the formatted code is just the
-- year the ID happens to be issued in, not part of the sequence's own numbering.
--
-- Seeded from the highest numeric suffix already used by any existing "NF-YYYY-NNNN"-style
-- employee_code, so a freshly-generated ID can never collide with one issued before this
-- migration ran. Legacy codes that don't match that shape (e.g. 'NF-0001', 'NF-00001') are not
-- part of this numbering scheme and are excluded from the seed calculation — same regex
-- EmployeeRepository previously used for its own (now removed) MAX+1 lookup.
CREATE SEQUENCE IF NOT EXISTS employee_code_seq AS BIGINT START WITH 1 INCREMENT BY 1 NO CYCLE;

SELECT setval(
    'employee_code_seq',
    COALESCE(
        (SELECT MAX(CAST(SPLIT_PART(employee_code, '-', 3) AS BIGINT))
         FROM employees
         WHERE employee_code ~ '^NF-[0-9]{4}-[0-9]+$'),
        0
    ),
    true
);
