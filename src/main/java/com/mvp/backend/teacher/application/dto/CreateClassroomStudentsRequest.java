package com.mvp.backend.teacher.application.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** O bien un alumno con nombre, o bien {@code count} cuentas sin nombre (1-40). */
public record CreateClassroomStudentsRequest(
        @Size(max = 160) String studentRealName,
        @Size(max = 2000) String notes,
        @Min(1) @Max(40) Integer count) {
}
