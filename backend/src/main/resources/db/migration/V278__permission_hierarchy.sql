-- Roles & Permissions enhancement, Phase 2 — permission hierarchy (Module > Submodule > Section) and per-action scope range.
--
-- Grouping and metadata only: NO role_permissions row is touched, so no one gains or loses any access.
-- Grants still live on the existing leaf rows in `modules` (the first segment of module:ACTION:SCOPE is
-- unchanged); the new parent rows (codes 'mod.*' and 'sub.*') carry no permissions. Authoritative
-- definition: backend/src/main/resources/permission-catalogue.yml, kept in step with this file by
-- PermissionCatalogueParityTest. GENERATED from that file — change the YAML, regenerate, don't hand-edit.
--
-- Additive and idempotent (IF NOT EXISTS / ON CONFLICT / re-runnable UPDATE).
-- Rollback: UPDATE modules SET parent_id = NULL; DELETE FROM modules WHERE code LIKE 'mod.%' OR code LIKE 'sub.%';
--           ALTER TABLE modules DROP COLUMN parent_id, DROP COLUMN node_type, DROP COLUMN sort_order,
--                               DROP COLUMN sensitivity, DROP COLUMN description;
--           ALTER TABLE permissions DROP COLUMN min_scope, DROP COLUMN max_scope;
ALTER TABLE modules
    ADD COLUMN IF NOT EXISTS parent_id   SMALLINT    NULL REFERENCES modules(id),
    ADD COLUMN IF NOT EXISTS node_type   VARCHAR(10) NOT NULL DEFAULT 'SUBMODULE',
    ADD COLUMN IF NOT EXISTS sort_order  INT         NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS sensitivity VARCHAR(6)  NOT NULL DEFAULT 'NORMAL',
    ADD COLUMN IF NOT EXISTS description VARCHAR(255) NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_modules_node_type') THEN
        ALTER TABLE modules ADD CONSTRAINT chk_modules_node_type CHECK (node_type IN ('MODULE', 'SUBMODULE', 'SECTION'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_modules_sensitivity') THEN
        ALTER TABLE modules ADD CONSTRAINT chk_modules_sensitivity CHECK (sensitivity IN ('NORMAL', 'HIGH'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_modules_parent ON modules(parent_id);

-- The scopes the backend actually evaluates for each permission (OWN < TEAM < ALL), as a contiguous range.
ALTER TABLE permissions
    ADD COLUMN IF NOT EXISTS min_scope VARCHAR(20) NOT NULL DEFAULT 'OWN',
    ADD COLUMN IF NOT EXISTS max_scope VARCHAR(20) NOT NULL DEFAULT 'ALL';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_permissions_scope_range') THEN
        ALTER TABLE permissions ADD CONSTRAINT chk_permissions_scope_range CHECK (
            min_scope IN ('OWN', 'TEAM', 'ALL') AND max_scope IN ('OWN', 'TEAM', 'ALL')
            AND (CASE min_scope WHEN 'OWN' THEN 0 WHEN 'TEAM' THEN 1 ELSE 2 END)
             <= (CASE max_scope WHEN 'OWN' THEN 0 WHEN 'TEAM' THEN 1 ELSE 2 END));
    END IF;
END $$;

-- Grouping rows (no permissions are ever attached to these).
INSERT INTO modules (code, display_name, custom_role_ready) VALUES
    ('mod.people', 'People', FALSE),
    ('mod.attendance', 'Attendance', FALSE),
    ('sub.attendance_requests', 'Attendance Requests', FALSE),
    ('mod.leave', 'Leave & Holidays', FALSE),
    ('mod.requests', 'Requests & Approvals', FALSE),
    ('mod.assets_expenses', 'Assets & Expenses', FALSE),
    ('sub.expenses', 'Expenses', FALSE),
    ('mod.talent', 'Talent & Performance', FALSE),
    ('sub.projects', 'Projects & Allocations', FALSE),
    ('mod.organization', 'Organization', FALSE),
    ('sub.org_structure', 'Organization Structure', FALSE),
    ('mod.documents', 'Documents & Compliance', FALSE),
    ('mod.reports_audit', 'Reports & Audit', FALSE),
    ('mod.payroll', 'Payroll & Compensation', FALSE),
    ('mod.administration', 'Administration', FALSE),
    ('sub.roles', 'Roles & Permissions', FALSE)
ON CONFLICT (code) DO NOTHING;

-- Hierarchy: (code, parent code, node type, sort order, sensitivity). Leaf display names are left as they are.
UPDATE modules m SET parent_id = p.id, node_type = v.node_type, sort_order = v.sort_order, sensitivity = v.sensitivity
FROM (VALUES
    ('mod.people', '', 'MODULE', 10, 'NORMAL'),
    ('employees', 'mod.people', 'SUBMODULE', 10, 'NORMAL'),
    ('employee_assignments', 'mod.people', 'SUBMODULE', 20, 'NORMAL'),
    ('onboarding', 'mod.people', 'SUBMODULE', 30, 'NORMAL'),
    ('mod.attendance', '', 'MODULE', 20, 'NORMAL'),
    ('attendance', 'mod.attendance', 'SUBMODULE', 10, 'NORMAL'),
    ('sub.attendance_requests', 'mod.attendance', 'SUBMODULE', 20, 'NORMAL'),
    ('regularization', 'sub.attendance_requests', 'SECTION', 10, 'NORMAL'),
    ('attendance_requests', 'sub.attendance_requests', 'SECTION', 20, 'NORMAL'),
    ('overtime', 'sub.attendance_requests', 'SECTION', 30, 'NORMAL'),
    ('exceptions', 'mod.attendance', 'SUBMODULE', 30, 'NORMAL'),
    ('penalization', 'mod.attendance', 'SUBMODULE', 40, 'NORMAL'),
    ('mod.leave', '', 'MODULE', 30, 'NORMAL'),
    ('leave', 'mod.leave', 'SUBMODULE', 10, 'NORMAL'),
    ('holidays', 'mod.leave', 'SUBMODULE', 20, 'NORMAL'),
    ('mod.requests', '', 'MODULE', 40, 'NORMAL'),
    ('helpdesk', 'mod.requests', 'SUBMODULE', 10, 'NORMAL'),
    ('hr_help_content', 'mod.requests', 'SUBMODULE', 20, 'NORMAL'),
    ('approval_rules', 'mod.requests', 'SUBMODULE', 30, 'HIGH'),
    ('mod.assets_expenses', '', 'MODULE', 50, 'NORMAL'),
    ('assets', 'mod.assets_expenses', 'SUBMODULE', 10, 'NORMAL'),
    ('sub.expenses', 'mod.assets_expenses', 'SUBMODULE', 20, 'NORMAL'),
    ('expenses', 'sub.expenses', 'SECTION', 10, 'NORMAL'),
    ('expenses_override', 'sub.expenses', 'SECTION', 20, 'HIGH'),
    ('mod.talent', '', 'MODULE', 60, 'NORMAL'),
    ('talent_skills', 'mod.talent', 'SUBMODULE', 10, 'NORMAL'),
    ('skill_ratings', 'mod.talent', 'SUBMODULE', 20, 'NORMAL'),
    ('bench', 'mod.talent', 'SUBMODULE', 30, 'NORMAL'),
    ('opportunities', 'mod.talent', 'SUBMODULE', 40, 'NORMAL'),
    ('sub.projects', 'mod.talent', 'SUBMODULE', 50, 'NORMAL'),
    ('projects', 'sub.projects', 'SECTION', 10, 'NORMAL'),
    ('project_allocations', 'sub.projects', 'SECTION', 20, 'NORMAL'),
    ('kudos', 'mod.talent', 'SUBMODULE', 60, 'NORMAL'),
    ('mod.organization', '', 'MODULE', 70, 'NORMAL'),
    ('sub.org_structure', 'mod.organization', 'SUBMODULE', 10, 'NORMAL'),
    ('organization', 'sub.org_structure', 'SECTION', 10, 'NORMAL'),
    ('organization_shifts', 'sub.org_structure', 'SECTION', 20, 'NORMAL'),
    ('mod.documents', '', 'MODULE', 80, 'NORMAL'),
    ('documents', 'mod.documents', 'SUBMODULE', 10, 'HIGH'),
    ('policies', 'mod.documents', 'SUBMODULE', 20, 'NORMAL'),
    ('announcements', 'mod.documents', 'SUBMODULE', 30, 'NORMAL'),
    ('mod.reports_audit', '', 'MODULE', 90, 'NORMAL'),
    ('reports', 'mod.reports_audit', 'SUBMODULE', 10, 'NORMAL'),
    ('audit', 'mod.reports_audit', 'SUBMODULE', 20, 'NORMAL'),
    ('mod.payroll', '', 'MODULE', 100, 'NORMAL'),
    ('payroll', 'mod.payroll', 'SUBMODULE', 10, 'HIGH'),
    ('compensation', 'mod.payroll', 'SUBMODULE', 20, 'HIGH'),
    ('mod.administration', '', 'MODULE', 110, 'NORMAL'),
    ('user_management', 'mod.administration', 'SUBMODULE', 10, 'HIGH'),
    ('sub.roles', 'mod.administration', 'SUBMODULE', 20, 'NORMAL'),
    ('roles', 'sub.roles', 'SECTION', 10, 'HIGH'),
    ('user_roles', 'sub.roles', 'SECTION', 20, 'HIGH'),
    ('ai_assistant', 'mod.administration', 'SUBMODULE', 30, 'NORMAL')
) AS v(code, parent_code, node_type, sort_order, sensitivity)
LEFT JOIN modules p ON p.code = NULLIF(v.parent_code, '')
WHERE m.code = v.code;

-- Descriptions (shown as tooltips in the role editor).
UPDATE modules m SET description = v.description
FROM (VALUES
    ('mod.people', 'Employee records, team assignments and onboarding.'),
    ('employees', 'The employee directory and employment records. Create and Edit expose personal and employment data.'),
    ('employee_assignments', 'Bulk shift, weekly-off and penalty-policy assignment (including import).'),
    ('onboarding', 'New-joiner checklists. Approve completes an onboarding flow.'),
    ('mod.attendance', 'Attendance records, corrections, requests and attendance policy.'),
    ('attendance', 'Attendance of other people. Edit covers cancelling penalties.'),
    ('sub.attendance_requests', 'Requests employees raise about their attendance, and their review.'),
    ('regularization', 'Attendance correction requests. Export reads the organisation-wide history; Configure lifts submission limits.'),
    ('attendance_requests', 'WFH and partial-day requests.'),
    ('overtime', 'Overtime requests.'),
    ('exceptions', 'Attendance exceptions (late, absent, missing punches) for a team or the organisation.'),
    ('penalization', 'Penalty policies and their allocation to employees.'),
    ('mod.leave', 'Leave administration and the holiday calendar.'),
    ('leave', 'Team/organisation leave, approvals and leave types.'),
    ('holidays', 'The holiday calendar.'),
    ('mod.requests', 'HR service desk, help content and approval workflow design.'),
    ('helpdesk', 'The HR ticket queue (reply, assign, change status).'),
    ('hr_help_content', 'Authoring and publishing FAQs and guides. Approve decides a submission.'),
    ('approval_rules', 'Approval routing rules (who approves an expense or payroll run, by amount).'),
    ('mod.assets_expenses', 'Company assets and expense claims.'),
    ('assets', 'Asset inventory, requests, assignment and return.'),
    ('sub.expenses', 'Expense claims and their approval.'),
    ('expenses', 'Claims, categories and payroll-ready claims.'),
    ('expenses_override', 'Decide an expense at the Super Admin stage regardless of the approval chain.'),
    ('mod.talent', 'Skills, bench, internal opportunities, projects and allocations.'),
    ('talent_skills', 'Talent dashboard, search, directory, skill master data and skill requests.'),
    ('skill_ratings', 'Viewing and endorsing a team''s skill ratings.'),
    ('bench', 'People between projects.'),
    ('opportunities', 'Posting and closing internal opportunities.'),
    ('sub.projects', 'Project reference data and allocating people to projects.'),
    ('projects', 'Projects and clients.'),
    ('project_allocations', 'Allocating people to projects and team capacity.'),
    ('kudos', 'Appreciating anyone in the organisation (relationship-based kudos need no permission).'),
    ('mod.organization', 'Organisation structure and attendance configuration.'),
    ('sub.org_structure', 'Masters used across the application.'),
    ('organization', 'Business units, departments, designations and locations.'),
    ('organization_shifts', 'Shifts, weekly-off policies, attendance rules.'),
    ('mod.documents', 'Employee documents, policies and announcements.'),
    ('documents', 'Verifying employee documents and configuring document types. Contains identity documents.'),
    ('policies', 'Company policies and acknowledgements.'),
    ('announcements', 'Company announcements. Configure lets someone manage announcements they did not create.'),
    ('mod.reports_audit', 'Reports and audit history.'),
    ('reports', 'Attendance-request and workforce reports.'),
    ('audit', 'The signed-in user''s own audited activity (other scopes are not implemented).'),
    ('mod.payroll', 'Payroll processing and compensation bands. Backend only — there is no payroll page yet.'),
    ('payroll', 'Pay setup, runs, holds, payslips. Not yet configurable on custom roles.'),
    ('compensation', 'Salary bands and their assignment.'),
    ('mod.administration', 'Users, roles and platform administration.'),
    ('user_management', 'Creating, editing, deactivating and deleting user accounts and resetting passwords.'),
    ('sub.roles', 'Defining roles and assigning them to people.'),
    ('roles', 'Creating roles and editing their permissions. Configure changes what a role can do.'),
    ('user_roles', 'Assigning roles to users.'),
    ('ai_assistant', 'API usage, billing and rate limits of the AI assistant.')
) AS v(code, description)
WHERE m.code = v.code;

-- Scope range per permission: (module, action, lowest, highest).
UPDATE permissions p SET min_scope = v.min_scope, max_scope = v.max_scope
FROM (VALUES
    ('employees', 'VIEW', 'TEAM', 'ALL'),
    ('employees', 'CREATE', 'ALL', 'ALL'),
    ('employees', 'EDIT', 'ALL', 'ALL'),
    ('employee_assignments', 'VIEW', 'TEAM', 'ALL'),
    ('employee_assignments', 'CREATE', 'TEAM', 'ALL'),
    ('employee_assignments', 'EDIT', 'TEAM', 'ALL'),
    ('onboarding', 'VIEW', 'ALL', 'ALL'),
    ('onboarding', 'CREATE', 'ALL', 'ALL'),
    ('onboarding', 'EDIT', 'ALL', 'ALL'),
    ('onboarding', 'APPROVE', 'ALL', 'ALL'),
    ('attendance', 'VIEW', 'TEAM', 'ALL'),
    ('attendance', 'EDIT', 'TEAM', 'ALL'),
    ('regularization', 'VIEW', 'TEAM', 'ALL'),
    ('regularization', 'APPROVE', 'TEAM', 'ALL'),
    ('regularization', 'EXPORT', 'ALL', 'ALL'),
    ('regularization', 'CONFIGURE', 'ALL', 'ALL'),
    ('attendance_requests', 'VIEW', 'TEAM', 'ALL'),
    ('attendance_requests', 'APPROVE', 'TEAM', 'ALL'),
    ('overtime', 'VIEW', 'TEAM', 'ALL'),
    ('overtime', 'APPROVE', 'TEAM', 'ALL'),
    ('exceptions', 'VIEW', 'TEAM', 'ALL'),
    ('penalization', 'VIEW', 'ALL', 'ALL'),
    ('penalization', 'CREATE', 'ALL', 'ALL'),
    ('penalization', 'EDIT', 'ALL', 'ALL'),
    ('penalization', 'DELETE', 'ALL', 'ALL'),
    ('leave', 'VIEW', 'TEAM', 'ALL'),
    ('leave', 'APPROVE', 'TEAM', 'ALL'),
    ('leave', 'CONFIGURE', 'ALL', 'ALL'),
    ('holidays', 'VIEW', 'ALL', 'ALL'),
    ('holidays', 'CREATE', 'ALL', 'ALL'),
    ('holidays', 'EDIT', 'ALL', 'ALL'),
    ('holidays', 'DELETE', 'ALL', 'ALL'),
    ('helpdesk', 'VIEW', 'ALL', 'ALL'),
    ('helpdesk', 'CREATE', 'ALL', 'ALL'),
    ('helpdesk', 'EDIT', 'ALL', 'ALL'),
    ('hr_help_content', 'VIEW', 'ALL', 'ALL'),
    ('hr_help_content', 'CREATE', 'ALL', 'ALL'),
    ('hr_help_content', 'EDIT', 'ALL', 'ALL'),
    ('hr_help_content', 'DELETE', 'ALL', 'ALL'),
    ('hr_help_content', 'APPROVE', 'TEAM', 'ALL'),
    ('approval_rules', 'VIEW', 'ALL', 'ALL'),
    ('approval_rules', 'CREATE', 'ALL', 'ALL'),
    ('approval_rules', 'EDIT', 'ALL', 'ALL'),
    ('approval_rules', 'DELETE', 'ALL', 'ALL'),
    ('approval_rules', 'CONFIGURE', 'ALL', 'ALL'),
    ('assets', 'VIEW', 'ALL', 'ALL'),
    ('assets', 'CREATE', 'ALL', 'ALL'),
    ('assets', 'EDIT', 'ALL', 'ALL'),
    ('assets', 'APPROVE', 'TEAM', 'ALL'),
    ('expenses', 'VIEW', 'TEAM', 'ALL'),
    ('expenses', 'APPROVE', 'TEAM', 'ALL'),
    ('expenses', 'CONFIGURE', 'ALL', 'ALL'),
    ('expenses_override', 'APPROVE', 'ALL', 'ALL'),
    ('talent_skills', 'VIEW', 'ALL', 'ALL'),
    ('talent_skills', 'CREATE', 'ALL', 'ALL'),
    ('talent_skills', 'APPROVE', 'ALL', 'ALL'),
    ('talent_skills', 'EXPORT', 'ALL', 'ALL'),
    ('talent_skills', 'CONFIGURE', 'ALL', 'ALL'),
    ('skill_ratings', 'VIEW', 'TEAM', 'ALL'),
    ('skill_ratings', 'EDIT', 'TEAM', 'ALL'),
    ('bench', 'VIEW', 'TEAM', 'ALL'),
    ('bench', 'CREATE', 'ALL', 'ALL'),
    ('bench', 'EDIT', 'ALL', 'ALL'),
    ('bench', 'CONFIGURE', 'ALL', 'ALL'),
    ('opportunities', 'VIEW', 'ALL', 'ALL'),
    ('opportunities', 'CREATE', 'ALL', 'ALL'),
    ('opportunities', 'APPROVE', 'ALL', 'ALL'),
    ('projects', 'VIEW', 'TEAM', 'ALL'),
    ('projects', 'CREATE', 'ALL', 'ALL'),
    ('projects', 'EDIT', 'ALL', 'ALL'),
    ('project_allocations', 'VIEW', 'TEAM', 'ALL'),
    ('project_allocations', 'CREATE', 'TEAM', 'ALL'),
    ('kudos', 'CREATE', 'ALL', 'ALL'),
    ('organization', 'CREATE', 'ALL', 'ALL'),
    ('organization', 'EDIT', 'ALL', 'ALL'),
    ('organization', 'DELETE', 'ALL', 'ALL'),
    ('organization_shifts', 'CREATE', 'ALL', 'ALL'),
    ('organization_shifts', 'EDIT', 'ALL', 'ALL'),
    ('organization_shifts', 'DELETE', 'ALL', 'ALL'),
    ('organization_shifts', 'CONFIGURE', 'ALL', 'ALL'),
    ('documents', 'VIEW', 'ALL', 'ALL'),
    ('documents', 'APPROVE', 'ALL', 'ALL'),
    ('documents', 'EXPORT', 'ALL', 'ALL'),
    ('documents', 'CONFIGURE', 'ALL', 'ALL'),
    ('policies', 'VIEW', 'ALL', 'ALL'),
    ('policies', 'CREATE', 'ALL', 'ALL'),
    ('policies', 'EDIT', 'ALL', 'ALL'),
    ('policies', 'DELETE', 'ALL', 'ALL'),
    ('announcements', 'VIEW', 'ALL', 'ALL'),
    ('announcements', 'CREATE', 'ALL', 'ALL'),
    ('announcements', 'EDIT', 'ALL', 'ALL'),
    ('announcements', 'DELETE', 'ALL', 'ALL'),
    ('announcements', 'CONFIGURE', 'ALL', 'ALL'),
    ('reports', 'VIEW', 'TEAM', 'ALL'),
    ('reports', 'EXPORT', 'TEAM', 'ALL'),
    ('audit', 'VIEW', 'OWN', 'OWN'),
    ('audit', 'EXPORT', 'OWN', 'OWN'),
    ('payroll', 'VIEW', 'ALL', 'ALL'),
    ('payroll', 'CONFIGURE', 'ALL', 'ALL'),
    ('compensation', 'VIEW', 'ALL', 'ALL'),
    ('compensation', 'CREATE', 'ALL', 'ALL'),
    ('compensation', 'EDIT', 'ALL', 'ALL'),
    ('user_management', 'VIEW', 'ALL', 'ALL'),
    ('user_management', 'CREATE', 'ALL', 'ALL'),
    ('user_management', 'EDIT', 'ALL', 'ALL'),
    ('user_management', 'DELETE', 'ALL', 'ALL'),
    ('roles', 'VIEW', 'ALL', 'ALL'),
    ('roles', 'CREATE', 'ALL', 'ALL'),
    ('roles', 'EDIT', 'ALL', 'ALL'),
    ('roles', 'DELETE', 'ALL', 'ALL'),
    ('roles', 'CONFIGURE', 'ALL', 'ALL'),
    ('user_roles', 'VIEW', 'ALL', 'ALL'),
    ('user_roles', 'EDIT', 'ALL', 'ALL'),
    ('ai_assistant', 'VIEW', 'ALL', 'ALL'),
    ('ai_assistant', 'CONFIGURE', 'ALL', 'ALL')
) AS v(module_code, action, min_scope, max_scope)
JOIN modules mo ON mo.code = v.module_code
WHERE p.module_id = mo.id AND p.action = v.action;
