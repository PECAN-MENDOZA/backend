package com.mvp.backend.research.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

import com.mvp.backend.research.application.dto.CreateTeacherRequest;
import com.mvp.backend.research.domain.repository.ResearcherRepository;
import com.mvp.backend.shared.exception.ConflictException;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.teacher.domain.model.Classroom;
import com.mvp.backend.teacher.domain.model.Teacher;
import com.mvp.backend.teacher.domain.model.TeacherStudentLink;
import com.mvp.backend.teacher.domain.repository.ClassroomRepository;
import com.mvp.backend.teacher.domain.repository.TeacherRepository;
import com.mvp.backend.teacher.domain.repository.TeacherStudentLinkRepository;

@ExtendWith(MockitoExtension.class)
class ResearchTeacherServiceTests {

    @Mock private TeacherRepository teacherRepository;
    @Mock private ResearcherRepository researcherRepository;
    @Mock private ClassroomRepository classroomRepository;
    @Mock private TeacherStudentLinkRepository linkRepository;
    @Mock private PasswordEncoder passwordEncoder;

    private ResearchTeacherService service;
    private final UUID researcherId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ResearchTeacherService(
                teacherRepository, researcherRepository, classroomRepository, linkRepository,
                new TemporaryPasswordGenerator(), passwordEncoder);
    }

    @Test
    void createsTeacherWithUsernameFromEmailAndTemporaryPassword() {
        when(teacherRepository.existsByEmail("Sofia.Garcia@colegio.edu.pe")).thenReturn(false);
        when(researcherRepository.findByEmail("Sofia.Garcia@colegio.edu.pe")).thenReturn(Optional.empty());
        when(teacherRepository.existsByUsername("sofia.garcia")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("encoded");
        when(teacherRepository.save(any(Teacher.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.createTeacher(researcherId,
                new CreateTeacherRequest("Sofia Garcia", "Sofia.Garcia@colegio.edu.pe", "Colegio San Martin"));

        assertThat(response.username()).isEqualTo("sofia.garcia");
        assertThat(response.email()).isEqualTo("Sofia.Garcia@colegio.edu.pe");
        assertThat(response.temporaryPassword()).matches("[A-HJ-NP-Za-km-z2-9]{10}");
    }

    @Test
    void usernameCollisionGetsNumericSuffix() {
        when(teacherRepository.existsByEmail(anyString())).thenReturn(false);
        when(researcherRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(teacherRepository.existsByUsername("ana")).thenReturn(true);
        when(teacherRepository.existsByUsername("ana-2")).thenReturn(true);
        when(teacherRepository.existsByUsername("ana-3")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("encoded");
        when(teacherRepository.save(any(Teacher.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.createTeacher(researcherId, new CreateTeacherRequest("Ana", "ana@x.edu", "X"));

        assertThat(response.username()).isEqualTo("ana-3");
    }

    @Test
    void emailInUseByTeacherOrResearcherIsConflict() {
        when(teacherRepository.existsByEmail("dup@x.edu")).thenReturn(true);

        assertThatThrownBy(() -> service.createTeacher(
                researcherId, new CreateTeacherRequest("D", "dup@x.edu", "X")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void resetPasswordMarksMustChange() {
        Teacher teacher = new Teacher("ana", "ana@x.edu", null, "X", "old");
        when(teacherRepository.findById(teacher.getId())).thenReturn(Optional.of(teacher));
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-temp");

        var response = service.resetPassword(teacher.getId());

        assertThat(response.temporaryPassword()).hasSize(10);
        assertThat(teacher.isMustChangePassword()).isTrue();
        assertThat(teacher.getPasswordHash()).isEqualTo("encoded-temp");
    }

    @Test
    void classroomDirectoryExposesUsernamesOnly() {
        Teacher teacher = new Teacher("ana", "ana@x.edu", null, "X", "hash");
        Classroom classroom = new Classroom(teacher, "3 B");
        Student student = new Student("tigre-07", "X", "hash");
        TeacherStudentLink link = new TeacherStudentLink(teacher, student, classroom, "cipher-real-name", null);
        when(classroomRepository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(classroom));
        when(linkRepository.findByClassroomIdAndDeletedAtIsNullOrderByCreatedAtAsc(classroom.getId()))
                .thenReturn(List.of(link));

        var directory = service.classroomDirectory();

        assertThat(directory).hasSize(1);
        assertThat(directory.get(0).teacherUsername()).isEqualTo("ana");
        assertThat(directory.get(0).students()).extracting(s -> s.username()).containsExactly("tigre-07");
        assertThat(directory.get(0).toString()).doesNotContain("cipher-real-name");
    }
}
