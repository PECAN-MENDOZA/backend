package com.mvp.backend.correction.infrastructure.ai;

import java.util.UUID;

record AiCorrectionRequest(
        String originalText,
        UUID studentId) {
}
