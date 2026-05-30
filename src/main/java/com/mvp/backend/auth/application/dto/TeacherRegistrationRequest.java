package com.mvp.backend.auth.application.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TeacherRegistrationRequest(
        @NotBlank @Size(min = 4, max = 80) String username,
        @NotBlank @Email @Size(max = 160) String email,
        @Size(max = 30) String phone,
        @NotBlank @Size(max = 120) String institution,
        @NotBlank @Size(min = 8, max = 72) String password) {
}
