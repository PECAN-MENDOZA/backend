package com.mvp.backend.teacher.application.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record MoveStudentRequest(@NotNull UUID classroomId) {
}
