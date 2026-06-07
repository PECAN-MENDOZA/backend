package com.mvp.backend.teacher.application.dto;

import java.time.Instant;
import java.util.UUID;

public record CreatedStudentAccountResponse(
        UUID linkId,
        UUID studentId,
        String username,
        String pin,
        String studentRealName,
        String institution,
        String notes,
        Instant createdAt) {
}
