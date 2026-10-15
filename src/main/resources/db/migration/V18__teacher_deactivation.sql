-- El investigador puede dar de baja a un docente: no entra al panel y sus alumnos no entran al
-- teclado. No se borra nada; reactivarlo deja todo como estaba.
ALTER TABLE teacher_users ADD COLUMN deactivated_at TIMESTAMP WITH TIME ZONE;
