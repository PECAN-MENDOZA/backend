package com.mvp.backend.auth.application.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record TeacherLoginRequest(@NotBlank @Email String email, @NotBlank String password) {
}
