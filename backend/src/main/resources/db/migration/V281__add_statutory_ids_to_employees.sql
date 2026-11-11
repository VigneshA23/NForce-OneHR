-- NForce OneHR — Flyway Migration V281
-- US-C15/US-C21 (ONEHR-579) — "My Finances" needs an employee's PAN, UAN and ESI number to show
-- statutory info alongside bank details; none of the three existed anywhere (confirmed by a
-- repo-wide search before writing this). National ID and bank details already exist as
-- self-service profile fields (see ProfileService's existing maskTrailing4 convention) — these
-- three join them the same way: self-service editable, masked on read in ProfileResponse.
ALTER TABLE employees ADD COLUMN IF NOT EXISTS pan_number VARCHAR(20);
ALTER TABLE employees ADD COLUMN IF NOT EXISTS uan_number VARCHAR(20);
ALTER TABLE employees ADD COLUMN IF NOT EXISTS esi_number VARCHAR(20);
