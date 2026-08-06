-- NForce OneHR — Flyway Migration V53
-- Records which authority (MANAGER / HR_ADMIN / SUPER_ADMIN) was actually exercised for each
-- approve/reject action — an actor holding multiple roles may act under any one of them
-- depending on the request's stage, so the role itself is worth recording alongside actor_by.
--
-- Nullable: existing rows predate this column and can't be retroactively attributed a role
-- with certainty. Every new row written by RegularizationService.recordApproval() populates it.
ALTER TABLE regularization_approvals ADD COLUMN actor_role VARCHAR(20);

ALTER TABLE regularization_approvals
    ADD CONSTRAINT regularization_approvals_actor_role_check
    CHECK (actor_role IS NULL OR actor_role IN ('MANAGER', 'HR_ADMIN', 'SUPER_ADMIN'));
