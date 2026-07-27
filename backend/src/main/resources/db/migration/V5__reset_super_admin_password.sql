-- NForce OneHR — Flyway Migration V5
-- Reset super admin password to ChangeMe123! (bcrypt, cost 12).
-- Run this in dev to restore a known working credential.

UPDATE users
SET    password_hash        = '$2y$12$f9SRG4O3saqrprWPu49o/OM9JuqW60PwcJlA3c0t92k.pPp9gpjyi',
       must_change_password = FALSE,
       updated_at           = now()
WHERE  email = 'superadmin@nforceone.com';
