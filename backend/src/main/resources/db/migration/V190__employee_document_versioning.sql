-- Retain document history on re-upload: an employee re-uploading a document for a document
-- type they already submitted must no longer overwrite the previous EmployeeDocument row in
-- place. Each submission becomes its own row ("version"); at most one row per
-- (employee_user_id, document_type_id) is ever "current" (superseded = false).
--
-- The old plain UNIQUE(employee_user_id, document_type_id) constraint only ever allowed one row
-- per employee/document-type, which is exactly the overwrite behavior being replaced — it's
-- swapped for a partial unique index that only applies to the current (non-superseded) row,
-- following the same pattern as V10's users.email partial unique index.
ALTER TABLE employee_documents
    DROP CONSTRAINT IF EXISTS employee_documents_employee_user_id_document_type_id_key;

ALTER TABLE employee_documents
    ADD COLUMN version_number     INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN superseded         BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN previous_version_id UUID REFERENCES employee_documents(id);

-- Existing rows are each the (only, current) version 1 of their employee/document-type —
-- the column defaults above already give them that shape; no backfill needed.

CREATE UNIQUE INDEX ux_employee_documents_current
    ON employee_documents (employee_user_id, document_type_id)
    WHERE NOT superseded;
