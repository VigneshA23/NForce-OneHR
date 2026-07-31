-- NForce OneHR — Flyway Migration V18
-- Tags each attendance_records row (V11) with where it came from, so a regularization
-- approval (V17) can be distinguished from a normal check-in/check-out punch.

ALTER TABLE attendance_records
    ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'SYSTEM'
        CHECK (source IN ('SYSTEM', 'REGULARIZATION'));
