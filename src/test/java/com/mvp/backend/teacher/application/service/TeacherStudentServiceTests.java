package com.mvp.backend.teacher.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.mvp.backend.shared.exception.BusinessException;
import com.mvp.backend.shared.security.PersonalDataCipher;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.teacher.application.dto.MoveStudentRequest;
import com.mvp.backend.teacher.application.dto.UpdateStudentLinkRequest;
import com.mvp.backend.teacher.domain.model.Classroom;
import com.mvp.backend.teacher.domain.model.Teacher;
import com.mvp.backend.teacher.domain.model.TeacherStudentLink;
import com.mvp.backend.teacher.domain.repository.ClassroomRepository;
import com.mvp.backend.teacher.domain.repository.TeacherStudentLinkRepository;

@ExtendWith(MockitoExtension.class)
class TeacherStudentServiceTests {

    @Mock private TeacherStudentLinkRepository linkRepository;
    @Mock private ClassroomRepository classroomRepository;
    @Mock private StudentCredentialGenerator credentials;
    @Mock private PersonalDataCipher personalDataCipher;
    @Mock private PasswordEncoder passwordEncoder;

    private TeacherStudentService service;
    private final UUID teacherId = UUID.randomUUID();
    private final Teacher teacher = new Teacher("sofia.garcia", "sofia@school.edu", null, "School 01", "encoded");

    @BeforeEach
    void setUp() {
        service = new TeacherStudentService(
                linkRepository, classroomRepository, credentials, personalDataCipher, passwordEncoder);
    }

    @Test
    void resetPinReturnsFourDigitPin() {
        Student student = new Student("tigre-07", "School 01", "old-hash");
        TeacherStudentLink link = link(student);
        stubOwnedLink(student, link);
        when(credentials.newPin()).thenReturn("0123");
        when(passwordEncoder.encode("0123")).thenReturn("encoded-new-pin");

        var response = service.resetPin(teacherId, student.getId());

        assertThat(response.pin()).matches("\\d{4}");
        assertThat(student.getPasswordHash()).isEqualTo("encoded-new-pin");
    }

    @Test
    void updateStudentReencryptsName() {
        Student student = new Student("tigre-07", "School 01", "hash");
        TeacherStudentLink link = link(student);
        stubOwnedLink(student, link);
        when(personalDataCipher.encrypt("Ana Torres")).thenReturn("new-cipher");
        when(personalDataCipher.decrypt("new-cipher")).thenReturn("Ana Torres");

        var response = service.updateStudent(
                teacherId, student.getId(), new UpdateStudentLinkRequest(" Ana Torres ", "Nota"));

        assertThat(link.getEncryptedStudentRealName()).isEqualTo("new-cipher");
        assertThat(link.getNotes()).isEqualTo("Nota");
        assertThat(response.studentRealName()).isEqualTo("Ana Torres");
        verify(personalDataCipher).encrypt("Ana Torres");
    }

    @Test
    void moveStudentRejectsArchivedTarget() {
        Student student = new Student("tigre-07", "School 01", "hash");
        TeacherStudentLink link = link(student);
        Classroom archived = new Classroom(teacher, "4.º A");
        archived.archive();
        stubOwnedLink(student, link);
        when(classroomRepository.findByIdAndTeacherId(archived.getId(), teacherId))
                .thenReturn(Optional.of(archived));

        assertThatThrownBy(() -> service.moveStudent(
                teacherId, student.getId(), new MoveStudentRequest(archived.getId())))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void deactivateMarksDeletedAt() {
        Student student = new Student("tigre-07", "School 01", "hash");
        TeacherStudentLink link = link(student);
        stubOwnedLink(student, link);

        service.deactivateStudent(teacherId, student.getId());

        assertThat(link.isDeleted()).isTrue();
    }

    private TeacherStudentLink link(Student student) {
        return new TeacherStudentLink(teacher, student, new Classroom(teacher, "3.º B"), "cipher", null);
    }

    private void stubOwnedLink(Student student, TeacherStudentLink link) {
        when(linkRepository.findByTeacherIdAndStudentIdAndDeletedAtIsNull(teacherId, student.getId()))
                .thenReturn(Optional.of(link));
    }
}
