package com.mvp.backend.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.mvp.backend.auth.application.dto.TeacherLoginRequest;
import com.mvp.backend.auth.application.dto.TeacherRegistrationRequest;
import com.mvp.backend.auth.domain.model.UserRole;
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
    private PasswordEncoder passwordEncoder;

    @Mock
    private TokenService tokenService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(studentRepository, teacherRepository, passwordEncoder, tokenService);
    }

    @Test
    void registersTeacherWithEncodedPassword() {
        var request = new TeacherRegistrationRequest("teacher_01", "teacher@upc.edu", null, "UPC", "Password123");
        when(passwordEncoder.encode("Password123")).thenReturn("encoded-password");
        when(teacherRepository.save(any(Teacher.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(tokenService.issue(any(UUID.class), any(UserRole.class)))
                .thenAnswer(invocation -> new AuthResponse(
                        invocation.getArgument(0),
                        "token",
                        Instant.now().plusSeconds(3600),
                        invocation.getArgument(1)));

        AuthResponse response = authService.registerTeacher(request);

        assertThat(response.role()).isEqualTo(UserRole.TEACHER);
        verify(passwordEncoder).encode("Password123");
        verify(teacherRepository).save(any(Teacher.class));
    }

    @Test
    void logsTeacherInUsingEmail() {
        var teacher = new Teacher("teacher_01", "teacher@upc.edu", null, "UPC", "encoded-password");
        when(teacherRepository.findByEmail("teacher@upc.edu")).thenReturn(Optional.of(teacher));
        when(passwordEncoder.matches("Password123", "encoded-password")).thenReturn(true);
        when(tokenService.issue(teacher.getId(), UserRole.TEACHER))
                .thenReturn(new AuthResponse(
                        teacher.getId(),
                        "token",
                        Instant.now().plusSeconds(3600),
                        UserRole.TEACHER));

        AuthResponse response = authService.loginTeacher(new TeacherLoginRequest("teacher@upc.edu", "Password123"));

        assertThat(response.userId()).isEqualTo(teacher.getId());
        assertThat(response.token()).isEqualTo("token");
    }
}
