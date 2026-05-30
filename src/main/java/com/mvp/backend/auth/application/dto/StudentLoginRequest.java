package com.mvp.backend.auth.application.dto;

import jakarta.validation.constraints.NotBlank;

public record StudentLoginRequest(@NotBlank String username, @NotBlank String password) {
}
