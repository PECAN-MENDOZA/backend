package com.mvp.backend.auth.application.dto;

import java.time.Instant;
import java.util.UUID;

import com.mvp.backend.auth.domain.model.UserRole;

public record AuthResponse(
        UUID userId,
        String token,
        Instant expiresAt,
        UserRole role,
        boolean mustChangePassword,
        // Nombre visible: fullName del docente; null si no lo hay (investigador, docente sin nombre).
        String displayName) {
}
