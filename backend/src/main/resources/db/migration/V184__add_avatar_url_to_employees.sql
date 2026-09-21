-- Lets an employee pick a generated avatar (a DiceBear URL) as an alternative to uploading a
-- real photo — mutually exclusive with profile_photo (see ProfileService: setting one clears the
-- other). Nullable, no default: most rows will never set this.
ALTER TABLE employees ADD COLUMN avatar_url TEXT;
