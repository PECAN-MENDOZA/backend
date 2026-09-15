-- Motivo del feedback del alumno (p. ej. UNDO cuando deshace una sugerencia aplicada).
ALTER TABLE correction_sessions ADD COLUMN feedback_reason VARCHAR(40);
