-- The manual two-step delete order in PayrollRunService#generateDraft (line items, then payslips)
-- is fragile under any overlapping/retried request — exactly what today's long run of timed-out
-- regenerate attempts produced: a payslip_line_items row left orphaned against a payslip the app
-- then can't delete ("violates foreign key constraint ... is still referenced"). ON DELETE CASCADE
-- makes deleting a payslip always take its own line items with it in the same statement, so this
-- class of bug can't recur regardless of request ordering.
ALTER TABLE payslip_line_items DROP CONSTRAINT payslip_line_items_payslip_id_fkey;
ALTER TABLE payslip_line_items ADD CONSTRAINT payslip_line_items_payslip_id_fkey
    FOREIGN KEY (payslip_id) REFERENCES payslips(id) ON DELETE CASCADE;
