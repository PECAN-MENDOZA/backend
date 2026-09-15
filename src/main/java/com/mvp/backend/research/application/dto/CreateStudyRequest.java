package com.mvp.backend.research.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateStudyRequest(
        @NotBlank @Size(max = 40) String code,
        @NotBlank @Size(max = 160) String title) {
}
