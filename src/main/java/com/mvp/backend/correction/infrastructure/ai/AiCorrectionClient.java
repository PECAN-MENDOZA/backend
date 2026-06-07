package com.mvp.backend.correction.infrastructure.ai;

import java.util.UUID;

public interface AiCorrectionClient {

    AiCorrectionResponse correct(String originalText, UUID studentId);

    /**
     * Envia a la IA la decision del alumno (sugerencia aceptada o rechazo) para
     * su entrenamiento por estudiante. Es best-effort: no debe interrumpir el
     * registro del feedback si la IA no esta disponible.
     */
    void sendFeedback(UUID studentId, String originalText, String selectedSuggestion, boolean accepted);
}
