package com.mvp.backend.teacher.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.student.domain.repository.StudentRepository;
import com.mvp.backend.teacher.domain.model.Classroom;
import com.mvp.backend.teacher.domain.model.Teacher;
import com.mvp.backend.teacher.domain.model.TeacherStudentLink;
import com.mvp.backend.teacher.domain.repository.ClassroomRepository;
import com.mvp.backend.teacher.domain.repository.TeacherRepository;
import com.mvp.backend.teacher.domain.repository.TeacherStudentLinkRepository;

@SpringBootTest
class ClassroomPersistenceTests {

    @Autowired
    private TeacherRepository teacherRepository;

    @Autowired
    private ClassroomRepository classroomRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private TeacherStudentLinkRepository linkRepository;

    @Test
    void linkBelongsToAClassroomOfItsTeacher() {
        Teacher teacher = teacherRepository.save(newTeacher());
        Classroom classroom = classroomRepository.save(new Classroom(teacher, "3.º B"));
        Student student = studentRepository.save(new Student("tigre-01-" + UUID.randomUUID(), "Colegio", "hash"));

        TeacherStudentLink link = linkRepository.save(
                new TeacherStudentLink(teacher, student, classroom, "cipher", null));

        assertThat(link.getClassroom().getId()).isEqualTo(classroom.getId());
        assertThat(linkRepository.countByClassroomIdAndDeletedAtIsNull(classroom.getId())).isEqualTo(1);
        assertThat(classroomRepository.findByTeacherIdOrderByArchivedAtAscCreatedAtAsc(teacher.getId()))
                .extracting(Classroom::getName).contains("3.º B");
    }

    @Test
    void linkWithoutClassroomIsRejected() {
        Teacher teacher = teacherRepository.save(newTeacher());
        Student student = studentRepository.save(new Student("leon-02-" + UUID.randomUUID(), "Colegio", "hash"));

        assertThatThrownBy(() -> linkRepository.saveAndFlush(
                new TeacherStudentLink(teacher, student, null, "cipher", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void activeClassroomsAreListedBeforeArchivedOnesRegardlessOfCreationOrder() {
        Teacher teacher = teacherRepository.save(newTeacher());
        // El salon archivado se crea primero (createdAt mas antiguo) para probar que el orden no depende
        // de como cada motor ordena los NULL de archivedAt (H2 los pone primero, Postgres al final).
        Classroom archived = classroomRepository.save(new Classroom(teacher, "Archivado"));
        archived.archive();
        classroomRepository.save(archived);
        Classroom active = classroomRepository.save(new Classroom(teacher, "Activo"));

        var ordered = classroomRepository.findByTeacherIdOrderByArchivedAtAscCreatedAtAsc(teacher.getId());

        assertThat(ordered).extracting(Classroom::getName).containsExactly("Activo", "Archivado");
    }

    @Test
    void teacherCreatedByResearcherMustChangeTemporaryPassword() {
        UUID researcherId = UUID.randomUUID();
        Teacher teacher = teacherRepository.save(
                new Teacher("ana.perez", UUID.randomUUID() + "@colegio.edu.pe", null, "Colegio", "hash", researcherId, true));

        assertThat(teacher.getCreatedBy()).isEqualTo(researcherId);
        assertThat(teacher.isMustChangePassword()).isTrue();
        teacher.changePassword("new-hash");
        assertThat(teacher.isMustChangePassword()).isFalse();
        assertThat(teacher.getPasswordHash()).isEqualTo("new-hash");
    }

    private static Teacher newTeacher() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return new Teacher("docente-" + suffix, suffix + "@colegio.edu.pe", null, "Colegio", "hash");
    }
}
