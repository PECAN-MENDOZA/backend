package com.mvp.backend.correction.infrastructure.ai;

import java.util.List;
import java.util.UUID;

public record AiCorrectionResponse(
        UUID studentId,
        String correctedText,
        long processingTimeMs,
        List<String> suggestions) {
}
