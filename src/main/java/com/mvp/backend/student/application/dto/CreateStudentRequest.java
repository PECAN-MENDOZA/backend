package com.mvp.backend.student.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateStudentRequest(
        @NotBlank @Size(min = 4, max = 80) String username,
        @NotBlank @Size(max = 120) String institution,
        @NotBlank @Size(min = 8, max = 72) String temporaryPassword) {
}
