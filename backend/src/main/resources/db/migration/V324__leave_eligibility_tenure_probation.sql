-- LV-06 (ONEHR-892): more "applies to" criteria for a leave type in a plan.
--
-- leave_plan_type_eligibility.rule_type gains MARITAL_STATUS (employees.marital_status, e.g. "Married") and
-- GRADE (the grade of the employee's designation). Values of one kind are OR'ed, kinds are AND'ed, as before.
-- leave_plan_type_rules gains two dated conditions, checked on the leave's start date:
--   min_tenure_months         the employee must have served this many months since joining (null = none).
--   allowed_during_probation  false = not available while on probation (before confirmation_date, or up to
--                             probation_end_date when there is no confirmation date). Credits of such a type
--                             are held during probation and caught up on the first crediting run after it.
--
-- Nothing changes on release day: no tenure minimum anywhere and every type stays allowed during probation.
-- Rollback: drop the two columns and restore the four-value CHECK (after deleting MARITAL_STATUS/GRADE rows).

ALTER TABLE leave_plan_type_eligibility DROP CONSTRAINT IF EXISTS leave_plan_type_eligibility_rule_type_check;
ALTER TABLE leave_plan_type_eligibility ADD CONSTRAINT leave_plan_type_eligibility_rule_type_check
    CHECK (rule_type IN ('GENDER', 'DEPARTMENT', 'LOCATION', 'EMPLOYMENT_TYPE', 'MARITAL_STATUS', 'GRADE'));

ALTER TABLE leave_plan_type_rules
    ADD COLUMN min_tenure_months        INTEGER CHECK (min_tenure_months IS NULL OR min_tenure_months BETWEEN 1 AND 600),
    ADD COLUMN allowed_during_probation BOOLEAN NOT NULL DEFAULT TRUE;
