package com.mvp.backend.auth.application.dto;

import java.time.Instant;
import java.util.UUID;

import com.mvp.backend.auth.domain.model.UserRole;

public record AuthResponse(UUID userId, String token, Instant expiresAt, UserRole role) {
}
