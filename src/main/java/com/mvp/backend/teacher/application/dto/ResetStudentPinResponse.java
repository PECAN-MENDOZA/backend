package com.mvp.backend.teacher.application.dto;

import java.util.UUID;

public record ResetStudentPinResponse(
        UUID studentId,
        String username,
        String pin) {
}
