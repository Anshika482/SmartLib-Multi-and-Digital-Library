-- ADMIN and SUPER_ADMIN self-registration. Both are applications: the account exists but cannot sign in until the
-- email is verified AND an existing SUPER_ADMIN approves it. They reuse the staff_assignments approval workflow;
-- a SUPER_ADMIN application has no library, so library_id becomes nullable (LIBRARIAN/ADMIN rows always set it,
-- enforced by the application).
ALTER TABLE staff_assignments MODIFY library_id BIGINT NULL;
ALTER TABLE staff_assignments ADD COLUMN designation VARCHAR(100) NULL AFTER employee_ref;
-- 'APPLICATION' = created by public self-registration; NULL = created by an invitation or the demo seed.
ALTER TABLE staff_assignments ADD COLUMN origin VARCHAR(16) NULL AFTER designation;
CREATE INDEX idx_staff_status ON staff_assignments (approval_status, created_at);
