package com.mvp.backend.teacher.application.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateStudentLinkRequest(
        @NotNull UUID studentId,
        @NotBlank @Size(max = 160) String studentRealName,
        @Size(max = 2000) String notes) {
}
