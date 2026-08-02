-- V24: Fix SA2 and SA3 employee records to use Executive dept and Chief Executive designation.
-- Their records were created by an earlier API seeding with wrong dept/designation.

UPDATE employees e
SET
    department_id  = (SELECT id FROM departments  WHERE name  = 'Executive'         LIMIT 1),
    designation_id = (SELECT id FROM designations WHERE title = 'Chief Executive'   LIMIT 1),
    employee_code  = 'NF-0002'
FROM users u
WHERE u.email    = 'superadmin2@nforceone.com'
  AND e.user_id  = u.id;

UPDATE employees e
SET
    department_id  = (SELECT id FROM departments  WHERE name  = 'Executive'         LIMIT 1),
    designation_id = (SELECT id FROM designations WHERE title = 'Chief Executive'   LIMIT 1),
    employee_code  = 'NF-0003'
FROM users u
WHERE u.email    = 'superadmin3@nforceone.com'
  AND e.user_id  = u.id;
