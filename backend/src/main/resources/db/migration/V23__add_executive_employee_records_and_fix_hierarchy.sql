-- V23: Give SA accounts employee records so they appear in the org hierarchy,
-- then fix manager assignments so the chart has correct root → branch → leaf structure.

-- 1. Add "Executive" department (idempotent)
INSERT INTO departments (name)
VALUES ('Executive')
ON CONFLICT (name) DO NOTHING;

-- 2. Add "Chief Executive" designation (idempotent)
INSERT INTO designations (title, grade)
VALUES ('Chief Executive', 'EX-1')
ON CONFLICT (title) DO NOTHING;

-- 3. Employee records for all three Super Admin accounts
--    Uses JOIN to avoid scalar-subquery ambiguity; ON CONFLICT skips if already exists.

--    SA1: superadmin@nforceone.com → "NForce Admin"
INSERT INTO employees (user_id, employee_code, full_name, department_id, designation_id,
                       employment_type, work_mode, joining_date)
SELECT u.id, 'NF-0001', 'NForce Admin', d.id, des.id, 'FULL_TIME', 'ONSITE', '2020-01-01'
FROM       users        u
JOIN       departments  d   ON d.name    = 'Executive'
JOIN       designations des ON des.title = 'Chief Executive'
WHERE u.email = 'superadmin@nforceone.com'
LIMIT 1
ON CONFLICT (user_id) DO NOTHING;

--    SA2: superadmin2@nforceone.com → "Anjali Kapoor"
INSERT INTO employees (user_id, employee_code, full_name, department_id, designation_id,
                       employment_type, work_mode, joining_date)
SELECT u.id, 'NF-0002', 'Anjali Kapoor', d.id, des.id, 'FULL_TIME', 'ONSITE', '2020-01-01'
FROM       users        u
JOIN       departments  d   ON d.name    = 'Executive'
JOIN       designations des ON des.title = 'Chief Executive'
WHERE u.email = 'superadmin2@nforceone.com'
LIMIT 1
ON CONFLICT (user_id) DO NOTHING;

--    SA3: superadmin3@nforceone.com → "Rohan Mehta"
INSERT INTO employees (user_id, employee_code, full_name, department_id, designation_id,
                       employment_type, work_mode, joining_date)
SELECT u.id, 'NF-0003', 'Rohan Mehta', d.id, des.id, 'FULL_TIME', 'ONSITE', '2020-01-01'
FROM       users        u
JOIN       departments  d   ON d.name    = 'Executive'
JOIN       designations des ON des.title = 'Chief Executive'
WHERE u.email = 'superadmin3@nforceone.com'
LIMIT 1
ON CONFLICT (user_id) DO NOTHING;

-- 4. Fix manager assignments — use JOINs, never scalar subqueries in WHERE.

-- manager@nforceone.com → close existing open row (if any), assign to SA1
UPDATE employee_manager_history h
SET    effective_to = NOW()
FROM   users u
WHERE  u.email            = 'manager@nforceone.com'
  AND  h.employee_user_id = u.id
  AND  h.effective_to     IS NULL;

INSERT INTO employee_manager_history (employee_user_id, manager_user_id, effective_from, created_at)
SELECT emp.id, mgr.id, NOW(), NOW()
FROM   users emp, users mgr
WHERE  emp.email = 'manager@nforceone.com'
  AND  mgr.email = 'superadmin@nforceone.com';

-- manager2@nforceone.com (Siddharth Rao) → close existing, assign to SA1
UPDATE employee_manager_history h
SET    effective_to = NOW()
FROM   users u
WHERE  u.email            = 'manager2@nforceone.com'
  AND  h.employee_user_id = u.id
  AND  h.effective_to     IS NULL;

INSERT INTO employee_manager_history (employee_user_id, manager_user_id, effective_from, created_at)
SELECT emp.id, mgr.id, NOW(), NOW()
FROM   users emp, users mgr
WHERE  emp.email = 'manager2@nforceone.com'
  AND  mgr.email = 'superadmin@nforceone.com';

-- hradmin@nforceone.com → close existing, assign to SA1
UPDATE employee_manager_history h
SET    effective_to = NOW()
FROM   users u
WHERE  u.email            = 'hradmin@nforceone.com'
  AND  h.employee_user_id = u.id
  AND  h.effective_to     IS NULL;

INSERT INTO employee_manager_history (employee_user_id, manager_user_id, effective_from, created_at)
SELECT emp.id, mgr.id, NOW(), NOW()
FROM   users emp, users mgr
WHERE  emp.email = 'hradmin@nforceone.com'
  AND  mgr.email = 'superadmin@nforceone.com';

-- manager3@nforceone.com (Priya Krishnan) and hradmin2@nforceone.com (Divya Nair)
-- already have SA2 (superadmin2@nforceone.com) as manager — no change needed.
