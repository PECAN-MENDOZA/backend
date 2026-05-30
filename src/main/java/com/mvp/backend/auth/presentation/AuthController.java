package com.mvp.backend.auth.presentation;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mvp.backend.auth.application.dto.AuthResponse;
import com.mvp.backend.auth.application.dto.StudentLoginRequest;
import com.mvp.backend.auth.application.dto.TeacherLoginRequest;
import com.mvp.backend.auth.application.dto.TeacherRegistrationRequest;
import com.mvp.backend.auth.application.service.AuthService;

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

    @PostMapping("/teachers/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse registerTeacher(@Valid @RequestBody TeacherRegistrationRequest request) {
        return authService.registerTeacher(request);
    }

    @PostMapping("/teachers/login")
    public AuthResponse loginTeacher(@Valid @RequestBody TeacherLoginRequest request) {
        return authService.loginTeacher(request);
    }
}
