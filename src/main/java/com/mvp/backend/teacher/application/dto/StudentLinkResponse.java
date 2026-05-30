package com.mvp.backend.teacher.application.dto;

import java.time.Instant;
import java.util.UUID;

public record StudentLinkResponse(
        UUID id,
        UUID studentId,
        String studentUsername,
        String studentRealName,
        String notes,
        Instant createdAt,
        Instant lastAccessAt) {
}
