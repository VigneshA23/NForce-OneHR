-- D1 follow-up: the organization's Default Shift was only ever identified by its exact name
-- (Shift.DEFAULT_SHIFT_NAME, "Default Shift" — see V161) in every rename/deactivate/delete guard.
-- That is a stable-enough identity in practice (renaming it is itself blocked), but it is still a
-- mutable-display-name lookup rather than a true stable identity marker, and nothing at the
-- database layer protects the row independently of the application's own guards. This column adds
-- that stable, non-name-based identity: a direct boolean flag on the row itself, set once here and
-- never exposed to the ordinary Shift create/update API (OrgService never writes it), so no future
-- shift can ever acquire it through normal use.
--
-- OrgService's rename/deactivate/delete guards now check this flag OR the legacy name match (see
-- OrgService#isDefaultShift) — additive, not a replacement, so neither guard weakens.
ALTER TABLE shifts
    ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE shifts SET is_default = TRUE WHERE name = 'Default Shift';

-- At most one shift may ever carry this flag — a partial unique index (not a CHECK, which cannot
-- reference other rows) enforces that at the database level independently of application code.
CREATE UNIQUE INDEX uq_shifts_single_default ON shifts (is_default) WHERE is_default = TRUE;
