-- Hasta V16, last_access_at se tocaba cada vez que el docente abria sus listas, no cuando el
-- alumno entraba. Se recalcula con lo unico fiable que queda: la ultima correccion del alumno
-- (sin correcciones queda NULL, "Sin ingresos"). Desde V16 lo actualizan el login y cada correccion.
UPDATE teacher_student_links l
SET last_access_at = (
    SELECT max(c.created_at)
    FROM correction_sessions c
    WHERE c.student_id = l.student_id
);
