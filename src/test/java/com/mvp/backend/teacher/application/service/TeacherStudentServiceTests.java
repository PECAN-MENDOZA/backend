package com.mvp.backend.teacher.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.mvp.backend.shared.security.PersonalDataCipher;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.student.domain.repository.StudentRepository;
import com.mvp.backend.teacher.application.dto.CreateLinkedStudentRequest;
import com.mvp.backend.teacher.domain.model.Classroom;
import com.mvp.backend.teacher.domain.model.Teacher;
import com.mvp.backend.teacher.domain.model.TeacherStudentLink;
import com.mvp.backend.teacher.domain.repository.ClassroomRepository;
import com.mvp.backend.teacher.domain.repository.TeacherRepository;
import com.mvp.backend.teacher.domain.repository.TeacherStudentLinkRepository;

@ExtendWith(MockitoExtension.class)
class TeacherStudentServiceTests {

    @Mock
    private TeacherRepository teacherRepository;

    @Mock
    private ClassroomRepository classroomRepository;

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private TeacherStudentLinkRepository linkRepository;

    @Mock
    private PersonalDataCipher personalDataCipher;

    @Mock
    private PasswordEncoder passwordEncoder;

    private TeacherStudentService teacherStudentService;

    @BeforeEach
    void setUp() {
        teacherStudentService = new TeacherStudentService(
                teacherRepository,
                classroomRepository,
                studentRepository,
                linkRepository,
                personalDataCipher,
                passwordEncoder);
    }

    @Test
    void createsPseudonymousAccountAndEncryptedTeacherLinkInOneOperation() {
        UUID teacherId = UUID.randomUUID();
        Teacher teacher = new Teacher("sofia.garcia", "sofia@school.edu", null, "School 01", "encoded");
        Classroom classroom = new Classroom(teacher, "3.º B");
        var request = new CreateLinkedStudentRequest("Nicolas Herrera", "Seguimiento mensual.");

        when(teacherRepository.findById(teacherId)).thenReturn(Optional.of(teacher));
        when(classroomRepository.findByTeacherIdOrderByArchivedAtAscCreatedAtAsc(teacherId))
                .thenReturn(List.of(classroom));
        when(studentRepository.existsByUsername(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-temporary-password");
        when(studentRepository.save(any(Student.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(personalDataCipher.encrypt("Nicolas Herrera")).thenReturn("encrypted-real-name");
        when(linkRepository.save(any(TeacherStudentLink.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = teacherStudentService.createLinkedStudent(teacherId, request);

        assertThat(response.username()).matches("[a-z]+-\\d{2,4}");
        assertThat(response.pin()).matches("\\d{4}");
        assertThat(response.studentRealName()).isEqualTo("Nicolas Herrera");
        assertThat(response.institution()).isEqualTo("School 01");

        ArgumentCaptor<Student> studentCaptor = ArgumentCaptor.forClass(Student.class);
        verify(studentRepository).save(studentCaptor.capture());
        assertThat(studentCaptor.getValue().getPasswordHash()).isEqualTo("encoded-temporary-password");
        assertThat(studentCaptor.getValue().getInstitution()).isEqualTo("School 01");
        verify(passwordEncoder).encode(response.pin());

        ArgumentCaptor<TeacherStudentLink> linkCaptor = ArgumentCaptor.forClass(TeacherStudentLink.class);
        verify(linkRepository).save(linkCaptor.capture());
        assertThat(linkCaptor.getValue().getEncryptedStudentRealName()).isEqualTo("encrypted-real-name");
        assertThat(linkCaptor.getValue().getStudent().getId()).isEqualTo(response.studentId());
    }

    @Test
    void resetsPinForLinkedStudent() {
        UUID teacherId = UUID.randomUUID();
        Teacher teacher = new Teacher("sofia.garcia", "sofia@school.edu", null, "School 01", "encoded");
        Student student = new Student("tigre-07", "School 01", "old-hash");
        Classroom classroom = new Classroom(teacher, "3.º B");
        TeacherStudentLink link = new TeacherStudentLink(teacher, student, classroom, "encrypted-real-name", null);

        when(linkRepository.findByTeacherIdAndStudentIdAndDeletedAtIsNull(teacherId, student.getId()))
                .thenReturn(Optional.of(link));
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-new-pin");

        var response = teacherStudentService.resetPin(teacherId, student.getId());

        assertThat(response.studentId()).isEqualTo(student.getId());
        assertThat(response.username()).isEqualTo("tigre-07");
        assertThat(response.pin()).matches("\\d{4}");
        assertThat(student.getPasswordHash()).isEqualTo("encoded-new-pin");
    }
}
