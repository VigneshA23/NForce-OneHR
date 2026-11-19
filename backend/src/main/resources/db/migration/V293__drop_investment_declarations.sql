-- "Import Investment Declaration" (§3.4) is removed — never wired into TDS, never shipped past
-- bookkeeping, and HR confirmed the business doesn't need it. Drop the table added in V286.
DROP TABLE IF EXISTS investment_declarations;
