-- Leave Type Paid/Unpaid classification (Organization Masters > Leave).
--
-- Assumption: the 3 leave types seeded by V19 (Annual/Sick/Casual) are, and always have been,
-- paid leave — V19's own policy notes describe them as ordinary quota-based leave with an opening
-- balance, with no existing unpaid/loss-of-pay concept anywhere in leave_types. DEFAULT 'PAID'
-- therefore reflects real existing semantics for every current row, not an arbitrary guess.

ALTER TABLE leave_types
    ADD COLUMN IF NOT EXISTS classification VARCHAR(10) NOT NULL DEFAULT 'PAID';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_leave_types_classification'
    ) THEN
        ALTER TABLE leave_types
            ADD CONSTRAINT chk_leave_types_classification
                CHECK (classification IN ('PAID', 'UNPAID'));
    END IF;
END $$;

UPDATE leave_types SET classification = 'PAID' WHERE code IN ('ANNUAL', 'SICK', 'CASUAL');
