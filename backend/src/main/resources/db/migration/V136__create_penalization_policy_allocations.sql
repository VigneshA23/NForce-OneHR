-- Effective-dated employee -> Penalisation Policy allocation, giving the org-wide Penalization
-- Policy Allocation screen (Administration -> Organization Masters -> Penalization Policy ->
-- Penalization Policy Allocation) a real assignment history instead of the single, non-dated
-- employees.penalisation_policy_id FK. Mirrors the effective_from/effective_to range shape
-- already used by penalization_policy_versions (V128) and employee_manager_history.
--
-- The legacy FK is left untouched and continues to act as the final fallback (see
-- PenalizationPolicyResolutionService) for any employee who has never been allocated through
-- this table, so no existing employee's resolved policy changes as a result of this migration.

CREATE TABLE penalization_policy_allocations (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_user_id       UUID NOT NULL REFERENCES employees(user_id),
    penalisation_policy_id UUID NOT NULL REFERENCES penalisation_policies(id),
    effective_from         DATE NOT NULL,
    effective_to           DATE,
    created_by             UUID NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by             UUID,
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT penalization_policy_allocations_range_check CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

CREATE INDEX idx_ppa_employee ON penalization_policy_allocations(employee_user_id);
CREATE INDEX idx_ppa_policy ON penalization_policy_allocations(penalisation_policy_id);
CREATE INDEX idx_ppa_employee_effective ON penalization_policy_allocations(employee_user_id, effective_from, effective_to);
