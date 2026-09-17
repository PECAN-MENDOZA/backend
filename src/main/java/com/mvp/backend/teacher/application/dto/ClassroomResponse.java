package com.mvp.backend.teacher.application.dto;

import java.time.Instant;
import java.util.UUID;

public record ClassroomResponse(UUID id, String name, long studentCount, Instant createdAt, Instant archivedAt) {
}
