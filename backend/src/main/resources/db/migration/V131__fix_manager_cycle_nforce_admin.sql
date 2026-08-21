-- Remove the bad active row that created a circular reporting chain between
-- superadmin@nforceone.com and superadmintwo@nforceone.com.
-- superadmin@nforceone.com is the true org root and must have no manager row.
DELETE FROM employee_manager_history
WHERE employee_user_id = (SELECT id FROM users WHERE email = 'superadmin@nforceone.com')
  AND manager_user_id  = (SELECT id FROM users WHERE email = 'superadmintwo@nforceone.com')
  AND effective_to IS NULL;
