-- Office-network check-in verification (record-only phase).
-- locations.office_cidrs: comma-separated public IPs/CIDRs of that office's network(s),
--   e.g. '203.0.113.10/32, 198.51.100.0/28'. NULL/blank = not configured, so punches are not judged.
-- attendance_punches.*_in_office: NULL = location not configured or IP unknown.
ALTER TABLE locations ADD COLUMN IF NOT EXISTS office_cidrs TEXT;

ALTER TABLE attendance_punches
    ADD COLUMN IF NOT EXISTS check_in_ip        VARCHAR(45),
    ADD COLUMN IF NOT EXISTS check_in_in_office BOOLEAN,
    ADD COLUMN IF NOT EXISTS check_out_ip        VARCHAR(45),
    ADD COLUMN IF NOT EXISTS check_out_in_office BOOLEAN;
