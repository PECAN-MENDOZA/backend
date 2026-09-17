package com.mvp.backend.teacher.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateClassroomRequest(@NotBlank @Size(max = 80) String name) {
}
