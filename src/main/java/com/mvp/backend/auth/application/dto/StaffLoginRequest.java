package com.mvp.backend.auth.application.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StaffLoginRequest(
        @Email @NotBlank @Size(max = 160) String email,
        @NotBlank @Size(max = 100) String password) {
}
