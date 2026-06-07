package com.mvp.backend.teacher.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateLinkedStudentRequest(
        @NotBlank @Size(max = 160) String studentRealName,
        @Size(max = 2000) String notes) {
}
