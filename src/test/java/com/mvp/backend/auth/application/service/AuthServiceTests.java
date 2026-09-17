package com.mvp.backend.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
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
import com.mvp.backend.auth.application.dto.TeacherLoginRequest;
import com.mvp.backend.auth.domain.model.UserRole;
import com.mvp.backend.research.domain.model.Researcher;
import com.mvp.backend.research.domain.repository.ResearcherRepository;
import com.mvp.backend.shared.exception.UnauthorizedException;
import com.mvp.backend.student.domain.repository.StudentRepository;
import com.mvp.backend.teacher.domain.model.Teacher;
import com.mvp.backend.teacher.domain.repository.TeacherRepository;

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

    private AuthService authService;

    @BeforeEach
    void setUp() {
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-password");
        authService = new AuthService(
                studentRepository, teacherRepository, researcherRepository, passwordEncoder, tokenService);
    }

    @Test
    void logsTeacherInUsingEmail() {
        var teacher = new Teacher(
                "teacher_01", "teacher@upc.edu", null, "UPC", "encoded-password", UUID.randomUUID(), true);
        when(teacherRepository.findByEmail("teacher@upc.edu")).thenReturn(Optional.of(teacher));
        when(passwordEncoder.matches("Password123", "encoded-password")).thenReturn(true);
        when(tokenService.issue(teacher.getId(), UserRole.TEACHER, true))
                .thenReturn(new AuthResponse(
                        teacher.getId(),
                        "token",
                        Instant.now().plusSeconds(3600),
                        UserRole.TEACHER,
                        true));

        AuthResponse response = authService.loginTeacher(new TeacherLoginRequest("teacher@upc.edu", "Password123"));

        assertThat(response.userId()).isEqualTo(teacher.getId());
        assertThat(response.token()).isEqualTo("token");
        assertThat(response.mustChangePassword()).isTrue();
    }

    @Test
    void logsResearcherInUsingEmail() {
        var researcher = new Researcher("authors@tesis.local", "encoded-password");
        when(researcherRepository.findByEmail("authors@tesis.local"))
                .thenReturn(Optional.of(researcher));
        when(passwordEncoder.matches("Research123", "encoded-password")).thenReturn(true);
        when(tokenService.issue(researcher.getId(), UserRole.RESEARCHER))
                .thenReturn(new AuthResponse(
                        researcher.getId(), "token", Instant.now().plusSeconds(3600), UserRole.RESEARCHER, false));

        AuthResponse response = authService.loginStaff(
                new StaffLoginRequest("authors@tesis.local", "Research123"));

        assertThat(response.role()).isEqualTo(UserRole.RESEARCHER);
    }

    @Test
    void unknownStaffEmailStillRunsAPasswordComparison() {
        when(researcherRepository.findByEmail("nobody@x.test")).thenReturn(Optional.empty());
        when(teacherRepository.findByEmail("nobody@x.test")).thenReturn(Optional.empty());

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
                "teacher_01", "teacher@upc.edu", null, "UPC", "teacher-hash", UUID.randomUUID(), true);
        when(researcherRepository.findByEmail("authors@tesis.local")).thenReturn(Optional.of(researcher));
        when(teacherRepository.findByEmail("authors@tesis.local")).thenReturn(Optional.empty());
        when(researcherRepository.findByEmail("teacher@upc.edu")).thenReturn(Optional.empty());
        when(teacherRepository.findByEmail("teacher@upc.edu")).thenReturn(Optional.of(teacher));
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
                "teacher_01", "teacher@upc.edu", null, "UPC", "teacher-hash", UUID.randomUUID(), true);
        when(researcherRepository.findByEmail("teacher@upc.edu")).thenReturn(Optional.empty());
        when(teacherRepository.findByEmail("teacher@upc.edu")).thenReturn(Optional.of(teacher));
        when(passwordEncoder.matches("Password123", "teacher-hash")).thenReturn(true);
        when(passwordEncoder.matches("Password123", "encoded-password")).thenReturn(false); // dummy researcher side
        when(tokenService.issue(teacher.getId(), UserRole.TEACHER, true))
                .thenReturn(new AuthResponse(
                        teacher.getId(), "token", Instant.now().plusSeconds(3600), UserRole.TEACHER, true));

        AuthResponse response = authService.loginStaff(new StaffLoginRequest("teacher@upc.edu", "Password123"));

        assertThat(response.role()).isEqualTo(UserRole.TEACHER);
        assertThat(response.mustChangePassword()).isTrue();
        verify(passwordEncoder, times(2)).matches(eq("Password123"), anyString());
    }

    @Test
    void changePasswordClearsMustChangeFlag() {
        var teacher = new Teacher(
                "teacher_01", "teacher@upc.edu", null, "UPC", "temporary-hash", UUID.randomUUID(), true);
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
                "teacher_01", "teacher@upc.edu", null, "UPC", "temporary-hash", UUID.randomUUID(), true);
        when(teacherRepository.findById(teacher.getId())).thenReturn(Optional.of(teacher));
        when(passwordEncoder.matches("Wrong123", "temporary-hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.changeTeacherPassword(
                teacher.getId(), new ChangePasswordRequest("Wrong123", "Permanent45")))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid teacher credentials");
    }
}
