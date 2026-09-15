package com.mvp.backend.research.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProtocolRequest(
        @NotBlank @Size(max = 5000) String taskAPrompt,
        @NotBlank @Size(max = 5000) String taskBPrompt) {
}
