-- Backfills EMPLOYEE_UPDATED/USER_UPDATED audit_log rows written before the fix to
-- EmployeeService.employeeSnapshot / UserManagementService.userSnapshot. Those rows still
-- have raw department/designation/location/manager UUIDs baked into before_state/after_state
-- (keys departmentId/designationId/locationId/managerId) — meaningless to a reader in the audit
-- detail popup. New rows already capture names instead (department/designation/location/
-- manager keys); this rewrites the existing rows the same way.
--
-- Resolves each id against its *current* name — the name at the time of that historical edit
-- isn't recoverable if the department/designation/location was renamed or the manager
-- reassigned since. A resolved-to-nothing id (record since deleted) becomes a JSON null, same
-- as an employee who had no department/designation/location/manager assigned at the time.
--
-- Filtered on the old key names still being present, so this is safe to run more than once —
-- once a row is backfilled it no longer matches and is left alone. Uses the jsonb_exists/
-- jsonb_exists_any function forms rather than the ?/?| operators — several JDBC-based tools
-- (Flyway included, depending on how a given statement is dispatched) can mistake a bare `?`
-- in the SQL text for a positional bind parameter, so the function forms sidestep that
-- ambiguity entirely.
UPDATE audit_log a
SET before_state = (
        (a.before_state - 'departmentId' - 'designationId' - 'locationId' - 'managerId')
        || jsonb_build_object(
            'department', (SELECT d.name FROM departments d WHERE d.id = (a.before_state->>'departmentId')::uuid),
            'designation', (SELECT g.title FROM designations g WHERE g.id = (a.before_state->>'designationId')::uuid),
            'location', (SELECT l.name FROM locations l WHERE l.id = (a.before_state->>'locationId')::uuid)
        )
        || CASE WHEN jsonb_exists(a.before_state, 'managerId') THEN
            jsonb_build_object('manager',
                COALESCE(
                    (SELECT e.full_name FROM employees e WHERE e.user_id = (a.before_state->>'managerId')::uuid),
                    (SELECT u.email FROM users u WHERE u.id = (a.before_state->>'managerId')::uuid)
                ))
           ELSE '{}'::jsonb
        END
    ),
    after_state = (
        (a.after_state - 'departmentId' - 'designationId' - 'locationId' - 'managerId')
        || jsonb_build_object(
            'department', (SELECT d.name FROM departments d WHERE d.id = (a.after_state->>'departmentId')::uuid),
            'designation', (SELECT g.title FROM designations g WHERE g.id = (a.after_state->>'designationId')::uuid),
            'location', (SELECT l.name FROM locations l WHERE l.id = (a.after_state->>'locationId')::uuid)
        )
        || CASE WHEN jsonb_exists(a.after_state, 'managerId') THEN
            jsonb_build_object('manager',
                COALESCE(
                    (SELECT e.full_name FROM employees e WHERE e.user_id = (a.after_state->>'managerId')::uuid),
                    (SELECT u.email FROM users u WHERE u.id = (a.after_state->>'managerId')::uuid)
                ))
           ELSE '{}'::jsonb
        END
    )
WHERE a.action IN ('EMPLOYEE_UPDATED', 'USER_UPDATED')
  AND a.before_state IS NOT NULL
  AND a.after_state IS NOT NULL
  AND (jsonb_exists_any(a.before_state, array['departmentId', 'designationId', 'locationId', 'managerId'])
       OR jsonb_exists_any(a.after_state, array['departmentId', 'designationId', 'locationId', 'managerId']));
