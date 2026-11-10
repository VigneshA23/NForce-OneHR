-- US-B04: an allocation can be ended early (actual_end_date) without rewriting its planned end_date.
-- Capacity aggregation treats COALESCE(actual_end_date, end_date) as the effective end.
ALTER TABLE project_allocations ADD COLUMN actual_end_date DATE;
ALTER TABLE project_allocations ADD CONSTRAINT project_allocations_actual_end_check
    CHECK (actual_end_date IS NULL OR actual_end_date >= start_date);
