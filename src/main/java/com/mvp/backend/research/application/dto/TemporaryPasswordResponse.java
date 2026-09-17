package com.mvp.backend.research.application.dto;

import java.util.UUID;

public record TemporaryPasswordResponse(UUID teacherId, String temporaryPassword) {
}
