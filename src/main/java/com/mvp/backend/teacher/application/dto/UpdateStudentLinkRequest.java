package com.mvp.backend.teacher.application.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateStudentLinkRequest(
        @NotNull @Size(max = 160) String studentRealName,
        @Size(max = 2000) String notes) {
}
