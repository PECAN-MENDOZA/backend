package com.mvp.backend.teacher.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.mvp.backend.shared.exception.BusinessException;
import com.mvp.backend.shared.exception.ConflictException;
import com.mvp.backend.shared.exception.ForbiddenException;
import com.mvp.backend.shared.security.PersonalDataCipher;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.student.domain.repository.StudentRepository;
import com.mvp.backend.teacher.application.dto.CreateClassroomRequest;
import com.mvp.backend.teacher.application.dto.CreateClassroomStudentsRequest;
import com.mvp.backend.teacher.application.dto.UpdateClassroomRequest;
import com.mvp.backend.teacher.domain.model.Classroom;
import com.mvp.backend.teacher.domain.model.Teacher;
import com.mvp.backend.teacher.domain.model.TeacherStudentLink;
import com.mvp.backend.teacher.domain.repository.ClassroomRepository;
import com.mvp.backend.teacher.domain.repository.TeacherRepository;
import com.mvp.backend.teacher.domain.repository.TeacherStudentLinkRepository;

@ExtendWith(MockitoExtension.class)
class ClassroomServiceTests {

    @Mock private TeacherRepository teacherRepository;
    @Mock private ClassroomRepository classroomRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private TeacherStudentLinkRepository linkRepository;
    @Mock private PersonalDataCipher personalDataCipher;
    @Mock private PasswordEncoder passwordEncoder;

    private ClassroomService service;
    private final UUID teacherId = UUID.randomUUID();
    private final Teacher teacher = new Teacher("sofia.garcia", "sofia@school.edu", null, "School 01", "hash");

    @BeforeEach
    void setUp() {
        service = new ClassroomService(
                teacherRepository, classroomRepository, studentRepository, linkRepository,
                new StudentCredentialGenerator(studentRepository), personalDataCipher, passwordEncoder);
    }

    @Test
    void createsClassroomForTeacher() {
        when(teacherRepository.findById(teacherId)).thenReturn(Optional.of(teacher));
        when(classroomRepository.existsByTeacherIdAndName(teacherId, "3.º B")).thenReturn(false);
        when(classroomRepository.save(any(Classroom.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.createClassroom(teacherId, new CreateClassroomRequest("3.º B"));

        assertThat(response.name()).isEqualTo("3.º B");
        assertThat(response.studentCount()).isZero();
        assertThat(response.archivedAt()).isNull();
    }

    @Test
    void rejectsDuplicateClassroomName() {
        when(teacherRepository.findById(teacherId)).thenReturn(Optional.of(teacher));
        when(classroomRepository.existsByTeacherIdAndName(teacherId, "3.º B")).thenReturn(true);

        assertThatThrownBy(() -> service.createClassroom(teacherId, new CreateClassroomRequest("3.º B")))
                .isInstanceOf(ConflictException.class);
        verify(classroomRepository, never()).save(any());
    }

    @Test
    void archivesAndRenamesClassroom() {
        Classroom classroom = new Classroom(teacher, "3.º B");
        when(classroomRepository.findByIdAndTeacherId(classroom.getId(), teacherId)).thenReturn(Optional.of(classroom));
        when(linkRepository.countByClassroomIdAndDeletedAtIsNull(classroom.getId())).thenReturn(2L);

        var response = service.updateClassroom(teacherId, classroom.getId(), new UpdateClassroomRequest("4.º A", true));

        assertThat(response.name()).isEqualTo("4.º A");
        assertThat(response.archivedAt()).isNotNull();
        assertThat(response.studentCount()).isEqualTo(2);
    }

    @Test
    void rejectsClassroomNameThatIsBlankAfterTrimming() {
        Classroom classroom = new Classroom(teacher, "3.º B");
        when(classroomRepository.findByIdAndTeacherId(classroom.getId(), teacherId)).thenReturn(Optional.of(classroom));

        assertThatThrownBy(() -> service.updateClassroom(
                teacherId, classroom.getId(), new UpdateClassroomRequest("   ", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Classroom name cannot be blank");

        assertThat(classroom.getName()).isEqualTo("3.º B");
    }

    @Test
    void createsOneNamedStudentInsideClassroom() {
        Classroom classroom = new Classroom(teacher, "3.º B");
        when(classroomRepository.findByIdAndTeacherId(classroom.getId(), teacherId)).thenReturn(Optional.of(classroom));
        when(studentRepository.existsByUsername(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-pin");
        when(studentRepository.save(any(Student.class))).thenAnswer(inv -> inv.getArgument(0));
        when(personalDataCipher.encrypt("Nicolas Herrera")).thenReturn("cipher-name");
        when(linkRepository.save(any(TeacherStudentLink.class))).thenAnswer(inv -> inv.getArgument(0));

        var created = service.createStudents(teacherId, classroom.getId(),
                new CreateClassroomStudentsRequest("Nicolas Herrera", "Seguimiento", null));

        assertThat(created).hasSize(1);
        assertThat(created.get(0).username()).matches("[a-z]+-\\d{2,4}");
        assertThat(created.get(0).pin()).matches("\\d{4}");
        assertThat(created.get(0).classroomId()).isEqualTo(classroom.getId());
        assertThat(created.get(0).studentRealName()).isEqualTo("Nicolas Herrera");
    }

    @Test
    void createsCountUnnamedStudents() {
        Classroom classroom = new Classroom(teacher, "3.º B");
        when(classroomRepository.findByIdAndTeacherId(classroom.getId(), teacherId)).thenReturn(Optional.of(classroom));
        when(studentRepository.existsByUsername(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-pin");
        when(studentRepository.save(any(Student.class))).thenAnswer(inv -> inv.getArgument(0));
        when(personalDataCipher.encrypt("")).thenReturn("cipher-empty");
        when(linkRepository.save(any(TeacherStudentLink.class))).thenAnswer(inv -> inv.getArgument(0));

        var created = service.createStudents(teacherId, classroom.getId(),
                new CreateClassroomStudentsRequest(null, null, 3));

        assertThat(created).hasSize(3);
        assertThat(created).extracting(c -> c.studentRealName()).containsOnly("");
        verify(linkRepository, times(3)).save(any(TeacherStudentLink.class));
    }

    @Test
    void rejectsStudentNameAndCountTogether() {
        Classroom classroom = new Classroom(teacher, "3.º B");
        when(classroomRepository.findByIdAndTeacherId(classroom.getId(), teacherId)).thenReturn(Optional.of(classroom));

        assertThatThrownBy(() -> service.createStudents(teacherId, classroom.getId(),
                new CreateClassroomStudentsRequest("Ana", null, 2)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Provide either studentRealName or count, not both");

        verify(linkRepository, never()).save(any());
    }

    @Test
    void rejectsNeitherNameNorCountAndArchivedClassroom() {
        Classroom classroom = new Classroom(teacher, "3.º B");
        when(classroomRepository.findByIdAndTeacherId(classroom.getId(), teacherId)).thenReturn(Optional.of(classroom));

        assertThatThrownBy(() -> service.createStudents(teacherId, classroom.getId(),
                new CreateClassroomStudentsRequest(null, null, null)))
                .isInstanceOf(BusinessException.class);

        classroom.archive();
        assertThatThrownBy(() -> service.createStudents(teacherId, classroom.getId(),
                new CreateClassroomStudentsRequest("Ana", null, null)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void classroomOfAnotherTeacherIsForbidden() {
        UUID classroomId = UUID.randomUUID();
        when(classroomRepository.findByIdAndTeacherId(classroomId, teacherId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listStudents(teacherId, classroomId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void listsClassroomsWithStudentCounts() {
        Classroom a = new Classroom(teacher, "3.º A");
        Classroom b = new Classroom(teacher, "3.º B");
        when(classroomRepository.findByTeacherIdOrderByArchivedAtAscCreatedAtAsc(teacherId)).thenReturn(List.of(a, b));
        when(linkRepository.countByClassroomIdAndDeletedAtIsNull(a.getId())).thenReturn(5L);
        when(linkRepository.countByClassroomIdAndDeletedAtIsNull(b.getId())).thenReturn(0L);

        var list = service.listClassrooms(teacherId);

        assertThat(list).extracting(r -> r.studentCount()).containsExactly(5L, 0L);
    }
}
