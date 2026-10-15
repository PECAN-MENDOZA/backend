-- Acceso del alumno: reiniciar el PIN cierra las sesiones abiertas y la baja quita el acceso.

-- (a) Momento del ultimo cambio de credenciales del alumno (reinicio de PIN). Un JWT de alumno
--     emitido antes de este instante (comparado en segundos) ya no sirve.
ALTER TABLE student_users ADD COLUMN credentials_changed_at TIMESTAMP WITH TIME ZONE;

-- (b) Cada peticion del alumno comprueba que tenga un vinculo activo: indice parcial por alumno.
CREATE INDEX idx_teacher_student_student_active ON teacher_student_links(student_id) WHERE deleted_at IS NULL;
