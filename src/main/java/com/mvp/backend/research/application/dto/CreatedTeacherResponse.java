package com.mvp.backend.research.application.dto;

import java.util.UUID;

public record CreatedTeacherResponse(
        UUID id,
        String username,
        String email,
        String institution,
        String temporaryPassword) {
}
