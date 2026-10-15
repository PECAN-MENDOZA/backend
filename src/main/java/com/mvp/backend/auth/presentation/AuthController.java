package com.mvp.backend.auth.presentation;

import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mvp.backend.auth.application.dto.AuthResponse;
import com.mvp.backend.auth.application.dto.ChangePasswordRequest;
import com.mvp.backend.auth.application.dto.StaffLoginRequest;
import com.mvp.backend.auth.application.dto.StudentLoginRequest;
import com.mvp.backend.auth.application.dto.TeacherLoginRequest;
import com.mvp.backend.auth.application.service.AuthService;
import com.mvp.backend.auth.domain.model.UserRole;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/students/login")
    public AuthResponse loginStudent(@Valid @RequestBody StudentLoginRequest request) {
        return authService.loginStudent(request);
    }

    @PostMapping("/teachers/login")
    public AuthResponse loginTeacher(@Valid @RequestBody TeacherLoginRequest request) {
        return authService.loginTeacher(request);
    }

    @PostMapping("/staff/login")
    public AuthResponse loginStaff(@Valid @RequestBody StaffLoginRequest request) {
        return authService.loginStaff(request);
    }

    @PostMapping("/teachers/change-password")
    @PreAuthorize("hasRole('TEACHER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeTeacherPassword(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ChangePasswordRequest request) {
        authService.changeTeacherPassword(UUID.fromString(jwt.getSubject()), request);
    }

    /** Cambio voluntario de contrasena para docente o investigador; /teachers/change-password se mantiene. */
    @PostMapping("/staff/change-password")
    @PreAuthorize("hasAnyRole('TEACHER', 'RESEARCHER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeStaffPassword(
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @Valid @RequestBody ChangePasswordRequest request) {
        // El rol sale de las autoridades ya validadas por @PreAuthorize (vienen del claim "role").
        boolean researcher = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_RESEARCHER".equals(authority.getAuthority()));
        authService.changeStaffPassword(
                UUID.fromString(jwt.getSubject()), researcher ? UserRole.RESEARCHER : UserRole.TEACHER, request);
    }
}
