package com.mvp.backend.correction.infrastructure.ai;

import java.util.UUID;

record AiFeedbackRequest(
        UUID studentId,
        String originalText,
        String selectedSuggestion,
        boolean accepted) {
}
