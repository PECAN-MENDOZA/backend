-- El alumno puede editar la sugerencia antes de aceptarla. Guardamos el texto
-- realmente insertado (final_text) y una marca de si difirió de la sugerencia base.
ALTER TABLE correction_sessions ADD COLUMN final_text TEXT;
ALTER TABLE correction_sessions ADD COLUMN was_edited BOOLEAN NOT NULL DEFAULT false;
