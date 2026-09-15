package com.mvp.backend.correction.infrastructure.ai;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Implementacion simulada del servicio de IA. Se usa mientras la IA real (BETO)
 * no esta lista, para poder desplegar el backend y avanzar la integracion del
 * teclado y el portal web. Hace eco del texto recibido y devuelve una sugerencia
 * fija. Solo registra en el log la operacion y el tamano del texto: el texto del
 * alumno (y el identificador que lo vincula) nunca va a los logs.
 *
 * <p>Activa con {@code app.ai.mode=stub} (valor por defecto si la propiedad falta).
 * Para volver a la IA real basta con {@code app.ai.mode=http}; no requiere cambios
 * de codigo.
 */
@Component
@ConditionalOnProperty(prefix = "app.ai", name = "mode", havingValue = "stub", matchIfMissing = true)
public class StubAiCorrectionClient implements AiCorrectionClient {

    private static final Logger log = LoggerFactory.getLogger(StubAiCorrectionClient.class);
    private static final String SIMULATED_SUGGESTION = "(sugerencia simulada)";
    private static final long SIMULATED_PROCESSING_TIME_MS = 5;
    private static final String SIMULATED_MODEL_VERSION = "stub";

    public StubAiCorrectionClient() {
        log.info("[AI-STUB] Modo IA simulado activo (app.ai.mode=stub). La IA real esta deshabilitada.");
    }

    @Override
    public AiCorrectionResponse correct(String originalText, UUID studentId) {
        log.info("[AI-STUB] stub correction: {} chars", originalText == null ? 0 : originalText.length());
        // Eco con sugerencias fijas: el objetivo es validar el flujo de UI, no la calidad de correccion.
        return new AiCorrectionResponse(
                studentId,
                originalText,
                SIMULATED_PROCESSING_TIME_MS,
                List.of(originalText, SIMULATED_SUGGESTION),
                SIMULATED_MODEL_VERSION);
    }

    @Override
    public void sendFeedback(UUID studentId, String originalText, String selectedSuggestion, boolean accepted) {
        // No-op: en modo simulado solo se registra la operacion (mantiene la semantica best-effort).
        log.info("[AI-STUB] stub feedback: accepted={}", accepted);
    }
}
