package com.mvp.backend.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.mvp.backend.auth.application.dto.AuthResponse;
import com.mvp.backend.auth.application.dto.ChangePasswordRequest;
import com.mvp.backend.auth.application.dto.StaffLoginRequest;
import com.mvp.backend.auth.application.dto.StudentLoginRequest;
import com.mvp.backend.auth.application.dto.TeacherLoginRequest;
import com.mvp.backend.auth.domain.model.UserRole;
import com.mvp.backend.research.domain.model.Researcher;
import com.mvp.backend.research.domain.repository.ResearcherRepository;
import com.mvp.backend.shared.exception.BusinessException;
import com.mvp.backend.shared.exception.UnauthorizedException;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.student.domain.repository.StudentRepository;
import com.mvp.backend.teacher.domain.model.Classroom;
import com.mvp.backend.teacher.domain.model.Teacher;
import com.mvp.backend.teacher.domain.model.TeacherStudentLink;
import com.mvp.backend.teacher.domain.repository.TeacherRepository;
import com.mvp.backend.teacher.domain.repository.TeacherStudentLinkRepository;

@ExtendWith(MockitoExtension.class)
class AuthServiceTests {

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private TeacherRepository teacherRepository;

    @Mock
    private ResearcherRepository researcherRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TokenService tokenService;

    @Mock
    private TeacherStudentLinkRepository linkRepository;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-password");
        authService = new AuthService(studentRepository, teacherRepository, researcherRepository, linkRepository,
                passwordEncoder, tokenService);
    }

    @Test
    void logsTeacherInUsingEmail() {
        var teacher = new Teacher(
                "teacher_01", "teacher@upc.edu", null, "UPC", "encoded-password", UUID.randomUUID(), true, null);
        when(teacherRepository.findByEmailIgnoreCase("teacher@upc.edu")).thenReturn(Optional.of(teacher));
        when(passwordEncoder.matches("Password123", "encoded-password")).thenReturn(true);
        when(tokenService.issue(teacher.getId(), UserRole.TEACHER, true, null))
                .thenReturn(new AuthResponse(
                        teacher.getId(),
                        "token",
                        Instant.now().plusSeconds(3600),
                        UserRole.TEACHER,
                        true,
                        null));

        AuthResponse response = authService.loginTeacher(new TeacherLoginRequest("teacher@upc.edu", "Password123"));

        assertThat(response.userId()).isEqualTo(teacher.getId());
        assertThat(response.token()).isEqualTo("token");
        assertThat(response.mustChangePassword()).isTrue();
    }

    @Test
    void logsResearcherInUsingEmail() {
        var researcher = new Researcher("authors@tesis.local", "encoded-password");
        when(researcherRepository.findByEmailIgnoreCase("authors@tesis.local"))
                .thenReturn(Optional.of(researcher));
        when(passwordEncoder.matches("Research123", "encoded-password")).thenReturn(true);
        when(tokenService.issue(researcher.getId(), UserRole.RESEARCHER, false, null))
                .thenReturn(new AuthResponse(
                        researcher.getId(), "token", Instant.now().plusSeconds(3600), UserRole.RESEARCHER, false, null));

        AuthResponse response = authService.loginStaff(
                new StaffLoginRequest("authors@tesis.local", "Research123"));

        assertThat(response.role()).isEqualTo(UserRole.RESEARCHER);
    }

    @Test
    void unknownStaffEmailStillRunsAPasswordComparison() {
        when(researcherRepository.findByEmailIgnoreCase("nobody@x.test")).thenReturn(Optional.empty());
        when(teacherRepository.findByEmailIgnoreCase("nobody@x.test")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.loginStaff(new StaffLoginRequest("nobody@x.test", "whatever")))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid staff credentials");
        // One dummy comparison per side (researcher, teacher): always exactly two.
        verify(passwordEncoder, times(2)).matches(eq("whatever"), anyString());
    }

    @Test
    void staffLoginAlwaysRunsExactlyTwoComparisonsWhateverTheAccountType() {
        var researcher = new Researcher("authors@tesis.local", "researcher-hash");
        var teacher = new Teacher(
                "teacher_01", "teacher@upc.edu", null, "UPC", "teacher-hash", UUID.randomUUID(), true, null);
        when(researcherRepository.findByEmailIgnoreCase("authors@tesis.local")).thenReturn(Optional.of(researcher));
        when(teacherRepository.findByEmailIgnoreCase("authors@tesis.local")).thenReturn(Optional.empty());
        when(researcherRepository.findByEmailIgnoreCase("teacher@upc.edu")).thenReturn(Optional.empty());
        when(teacherRepository.findByEmailIgnoreCase("teacher@upc.edu")).thenReturn(Optional.of(teacher));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> authService.loginStaff(new StaffLoginRequest("authors@tesis.local", "wrong")))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> authService.loginStaff(new StaffLoginRequest("teacher@upc.edu", "wrong")))
                .isInstanceOf(UnauthorizedException.class);

        // Researcher email: real researcher hash + dummy for the teacher side.
        verify(passwordEncoder).matches("wrong", "researcher-hash");
        // Teacher email: dummy for the researcher side + real teacher hash.
        verify(passwordEncoder).matches("wrong", "teacher-hash");
        verify(passwordEncoder, times(2)).matches("wrong", "encoded-password");
        verify(passwordEncoder, times(4)).matches(anyString(), anyString());
    }

    @Test
    void teacherWithTheRightPasswordLogsInThroughStaffLoginWithTwoComparisons() {
        var teacher = new Teacher(
                "teacher_01", "teacher@upc.edu", null, "UPC", "teacher-hash", UUID.randomUUID(), true, null);
        when(researcherRepository.findByEmailIgnoreCase("teacher@upc.edu")).thenReturn(Optional.empty());
        when(teacherRepository.findByEmailIgnoreCase("teacher@upc.edu")).thenReturn(Optional.of(teacher));
        when(passwordEncoder.matches("Password123", "teacher-hash")).thenReturn(true);
        when(passwordEncoder.matches("Password123", "encoded-password")).thenReturn(false); // dummy researcher side
        when(tokenService.issue(teacher.getId(), UserRole.TEACHER, true, null))
                .thenReturn(new AuthResponse(
                        teacher.getId(), "token", Instant.now().plusSeconds(3600), UserRole.TEACHER, true, null));

        AuthResponse response = authService.loginStaff(new StaffLoginRequest("teacher@upc.edu", "Password123"));

        assertThat(response.role()).isEqualTo(UserRole.TEACHER);
        assertThat(response.mustChangePassword()).isTrue();
        verify(passwordEncoder, times(2)).matches(eq("Password123"), anyString());
    }

    @Test
    void changePasswordClearsMustChangeFlag() {
        var teacher = new Teacher(
                "teacher_01", "teacher@upc.edu", null, "UPC", "temporary-hash", UUID.randomUUID(), true, null);
        when(teacherRepository.findById(teacher.getId())).thenReturn(Optional.of(teacher));
        when(passwordEncoder.matches("Temporary23", "temporary-hash")).thenReturn(true);
        when(passwordEncoder.encode("Permanent45")).thenReturn("permanent-hash");

        authService.changeTeacherPassword(
                teacher.getId(), new ChangePasswordRequest("Temporary23", "Permanent45"));

        assertThat(teacher.isMustChangePassword()).isFalse();
        assertThat(teacher.getPasswordHash()).isEqualTo("permanent-hash");
    }

    @Test
    void changePasswordRejectsWrongCurrent() {
        var teacher = new Teacher(
                "teacher_01", "teacher@upc.edu", null, "UPC", "temporary-hash", UUID.randomUUID(), true, null);
        when(teacherRepository.findById(teacher.getId())).thenReturn(Optional.of(teacher));
        when(passwordEncoder.matches("Wrong123", "temporary-hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.changeTeacherPassword(
                teacher.getId(), new ChangePasswordRequest("Wrong123", "Permanent45")))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid teacher credentials");
    }

    @Test
    void studentWithoutActiveLinkIsToldTheAccountIsDeactivatedOnlyWithTheRightPin() {
        var student = new Student("tigre-07", "School", "pin-hash");
        when(studentRepository.findByUsernameIgnoreCase("tigre-07")).thenReturn(Optional.of(student));
        when(passwordEncoder.matches("1234", "pin-hash")).thenReturn(true);
        when(passwordEncoder.matches("9999", "pin-hash")).thenReturn(false);
        when(linkRepository.findByStudentIdAndDeletedAtIsNullAndTeacherDeactivatedAtIsNull(student.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> authService.loginStudent(new StudentLoginRequest("tigre-07", "1234")))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Student account is deactivated");
        assertThatThrownBy(() -> authService.loginStudent(new StudentLoginRequest("tigre-07", "9999")))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid student credentials");
        assertThatThrownBy(() -> authService.loginStudent(new StudentLoginRequest("nadie-01", "1234")))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid student credentials");
    }

    @Test
    void deactivatedTeacherIsToldOnlyWithTheRightPasswordAndStaffLoginKeepsTwoComparisons() {
        var teacher = new Teacher("ana", "ana@x.edu", null, "School", "teacher-hash");
        teacher.deactivate();
        when(researcherRepository.findByEmailIgnoreCase("ana@x.edu")).thenReturn(Optional.empty());
        when(teacherRepository.findByEmailIgnoreCase("ana@x.edu")).thenReturn(Optional.of(teacher));
        when(passwordEncoder.matches("Right123", "teacher-hash")).thenReturn(true);
        when(passwordEncoder.matches("Wrong123", "teacher-hash")).thenReturn(false);
        when(passwordEncoder.matches("Right123", "encoded-password")).thenReturn(false); // lado investigador ficticio
        when(passwordEncoder.matches("Wrong123", "encoded-password")).thenReturn(false);

        assertThatThrownBy(() -> authService.loginStaff(new StaffLoginRequest("ana@x.edu", "Right123")))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Teacher account is deactivated");
        assertThatThrownBy(() -> authService.loginStaff(new StaffLoginRequest("ana@x.edu", "Wrong123")))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid staff credentials");
        assertThatThrownBy(() -> authService.loginTeacher(new TeacherLoginRequest("ana@x.edu", "Right123")))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Teacher account is deactivated");
        assertThatThrownBy(() -> authService.loginTeacher(new TeacherLoginRequest("ana@x.edu", "Wrong123")))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid teacher credentials");
        // Login de staff: real del docente + ficticia del investigador (2); loginTeacher: 1.
        verify(passwordEncoder, times(3)).matches(eq("Right123"), anyString());
        verify(passwordEncoder).matches("Right123", "encoded-password");
    }

    @Test
    void studentLoginMarksLastAccessOnEveryActiveLink() {
        var student = new Student("tigre-07", "School", "pin-hash");
        var teacher = new Teacher("ana", "ana@x.edu", null, "School", "hash");
        var link = new TeacherStudentLink(teacher, student, new Classroom(teacher, "3.º B"), "cipher", null);
        // El alias llega con espacios y mayuscula inicial (como lo deja el celular): se busca sin distinguirlas.
        when(studentRepository.findByUsernameIgnoreCase("Tigre-07")).thenReturn(Optional.of(student));
        when(passwordEncoder.matches("1234", "pin-hash")).thenReturn(true);
        when(linkRepository.findByStudentIdAndDeletedAtIsNullAndTeacherDeactivatedAtIsNull(student.getId())).thenReturn(List.of(link));

        authService.loginStudent(new StudentLoginRequest(" Tigre-07 ", "1234"));

        assertThat(link.getLastAccessAt()).isNotNull();
        verify(tokenService).issue(student.getId(), UserRole.STUDENT);
    }

    @Test
    void teacherLoginReturnsFullNameAsDisplayName() {
        var teacher = new Teacher(
                "ana.qa", "ana.qa@x.edu", null, "UPC", "teacher-hash", UUID.randomUUID(), false, "Ana QA Torres");
        when(teacherRepository.findByEmailIgnoreCase("ANA.QA@x.edu")).thenReturn(Optional.of(teacher));
        when(passwordEncoder.matches("Password123", "teacher-hash")).thenReturn(true);

        authService.loginTeacher(new TeacherLoginRequest("ANA.QA@x.edu", "Password123"));

        verify(tokenService).issue(teacher.getId(), UserRole.TEACHER, false, "Ana QA Torres");
    }

    @Test
    void researcherChangesOwnPassword() {
        var researcher = new Researcher("authors@tesis.local", "old-hash");
        when(researcherRepository.findById(researcher.getId())).thenReturn(Optional.of(researcher));
        when(passwordEncoder.matches("Research123", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("Research456")).thenReturn("new-hash");

        authService.changeStaffPassword(
                researcher.getId(), UserRole.RESEARCHER, new ChangePasswordRequest("Research123", "Research456"));

        assertThat(researcher.getPasswordHash()).isEqualTo("new-hash");
    }

    @Test
    void staffChangePasswordRejectsWrongCurrentAndSamePassword() {
        var teacher = new Teacher(
                "teacher_01", "teacher@upc.edu", null, "UPC", "current-hash", UUID.randomUUID(), false, null);
        when(teacherRepository.findById(teacher.getId())).thenReturn(Optional.of(teacher));
        when(passwordEncoder.matches("Wrong12345", "current-hash")).thenReturn(false);
        when(passwordEncoder.matches("Current123", "current-hash")).thenReturn(true);

        assertThatThrownBy(() -> authService.changeStaffPassword(
                teacher.getId(), UserRole.TEACHER, new ChangePasswordRequest("Wrong12345", "Permanent45")))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> authService.changeStaffPassword(
                teacher.getId(), UserRole.TEACHER, new ChangePasswordRequest("Current123", "Current123")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("New password must be different");
    }
}
