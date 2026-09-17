-- Salones: cada vinculo docente-alumno pertenece a un salon del docente.
-- Docentes: los crea el investigador con contrasena temporal (registro publico eliminado).

CREATE TABLE classrooms (
    id UUID PRIMARY KEY,
    teacher_id UUID NOT NULL REFERENCES teacher_users(id),
    name VARCHAR(80) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    archived_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_classroom_teacher_name UNIQUE (teacher_id, name)
);

CREATE INDEX idx_classrooms_teacher ON classrooms(teacher_id);

ALTER TABLE teacher_student_links ADD COLUMN classroom_id UUID REFERENCES classrooms(id);

-- Los vinculos existentes pasan a un salon "Sin salon" por docente.
INSERT INTO classrooms (id, teacher_id, name, created_at)
SELECT gen_random_uuid(), t.id, 'Sin salón', NOW()
FROM teacher_users t
WHERE EXISTS (SELECT 1 FROM teacher_student_links l WHERE l.teacher_id = t.id);

UPDATE teacher_student_links l
SET classroom_id = c.id
FROM classrooms c
WHERE c.teacher_id = l.teacher_id AND c.name = 'Sin salón' AND l.classroom_id IS NULL;

ALTER TABLE teacher_student_links ALTER COLUMN classroom_id SET NOT NULL;
CREATE INDEX idx_links_classroom ON teacher_student_links(classroom_id);

ALTER TABLE teacher_users ADD COLUMN created_by UUID REFERENCES researcher_users(id);
ALTER TABLE teacher_users ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
