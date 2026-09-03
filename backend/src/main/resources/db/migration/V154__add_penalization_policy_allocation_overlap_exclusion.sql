-- Section 6: the application-level overlap check in PenalizationPolicyAllocationService#checkOverlap
-- has been the only thing preventing two allocation rows from covering the same employee/date
-- range since V136 — this adds a database-level backstop so the invariant holds even against a
-- future write path that bypasses that service (a race between two concurrent requests, a direct
-- data fix, etc.), not just today's application code.
--
-- btree_gist is required for the "=" operator on a UUID column to participate in a GiST exclusion
-- constraint (GiST has no native UUID equality support otherwise). daterange(..., '[]') mirrors
-- the existing CHECK constraint's inclusive-on-both-ends semantics exactly, and a NULL
-- effective_to already means "open-ended, extends to +infinity" the same way the application code
-- (findOverlapping) already treats it.
--
-- Safety: this ALTER TABLE simply fails (and the migration aborts, leaving the schema unchanged)
-- if any existing rows already violate the constraint — Postgres itself is the pre-migration data
-- check here, a stronger guarantee than any read-only scan could give, since it's the same engine
-- that will enforce the constraint going forward.
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE penalization_policy_allocations
    ADD CONSTRAINT penalization_policy_allocations_no_overlap
    EXCLUDE USING gist (
        employee_user_id WITH =,
        daterange(effective_from, effective_to, '[]') WITH &&
    );
