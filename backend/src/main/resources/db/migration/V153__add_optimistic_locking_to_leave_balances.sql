-- Section 6 of the production-readiness pass: leave_balances.used_days is read-modify-written by
-- up to four independent code paths (leave submission's reservation, leave approval, penalty
-- deduction, and penalty-reversal restoration) with nothing else coordinating between them. A
-- version column turns a silent lost-update race (two concurrent writers, one overwrites the
-- other) into an ObjectOptimisticLockingFailureException the caller can retry, per JPA's standard
-- @Version contract.
ALTER TABLE leave_balances
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
