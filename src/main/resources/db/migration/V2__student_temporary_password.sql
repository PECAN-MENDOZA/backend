ALTER TABLE student_users
    ADD COLUMN password_change_required BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE student_users
SET password_change_required = FALSE;
