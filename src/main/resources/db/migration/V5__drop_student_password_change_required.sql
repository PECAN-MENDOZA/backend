-- El cambio de contrasena obligatorio se elimina: los alumnos usan un alias
-- amigable (palabra-NN) y un PIN de 4 digitos gestionado por el profesor
-- (ver docs/superpowers/specs/2026-06-06-simplificacion-acceso-alumnos-design.md).

ALTER TABLE student_users DROP COLUMN password_change_required;
