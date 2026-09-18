package com.mvp.backend.research.application.dto;

import java.time.Instant;
import java.util.UUID;

public record TeacherSummaryResponse(
        UUID id,
        String username,
        String fullName,
        String email,
        String institution,
        Instant createdAt,
        boolean mustChangePassword,
        long classroomCount,
        long studentCount) {
}
