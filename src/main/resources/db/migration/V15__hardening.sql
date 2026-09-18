-- Endurecimiento: nombre de salon reutilizable tras archivarlo y nombre completo del docente.

-- (a) La unicidad (teacher_id, name) solo aplica a salones activos: un docente puede volver a
--     crear "3.º B" cuando el anterior quedo archivado.
ALTER TABLE classrooms DROP CONSTRAINT uk_classroom_teacher_name;
CREATE UNIQUE INDEX uk_classroom_teacher_name_active ON classrooms(teacher_id, name) WHERE archived_at IS NULL;

-- (b) Nombre completo del docente (lo captura el investigador al crear la cuenta).
ALTER TABLE teacher_users ADD COLUMN full_name VARCHAR(120);
