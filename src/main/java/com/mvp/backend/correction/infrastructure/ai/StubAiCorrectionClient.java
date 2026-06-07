package com.mvp.backend.correction.infrastructure.ai;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * Implementacion simulada del servicio de IA. Se usa mientras la IA real (BETO)
 * no esta lista, para poder desplegar el backend y avanzar la integracion del
 * teclado y el portal web. Hace eco del texto recibido, devuelve una sugerencia
 * fija y registra en el log el JSON de entrada y salida para supervisar el
 * contrato durante la integracion.
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

    private final ObjectMapper objectMapper;

    public StubAiCorrectionClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        log.info("[AI-STUB] Modo IA simulado activo (app.ai.mode=stub). La IA real esta deshabilitada.");
    }

    @Override
    public AiCorrectionResponse correct(String originalText, UUID studentId) {
        logJson(">> correct request", new AiCorrectionRequest(originalText, studentId));
        // Eco con sugerencias fijas: el objetivo es validar el flujo de UI, no la calidad de correccion.
        AiCorrectionResponse response = new AiCorrectionResponse(
                studentId,
                originalText,
                SIMULATED_PROCESSING_TIME_MS,
                List.of(originalText, SIMULATED_SUGGESTION));
        logJson("<< correct response", response);
        return response;
    }

    @Override
    public void sendFeedback(UUID studentId, String originalText, String selectedSuggestion, boolean accepted) {
        // No-op: en modo simulado solo se registra el payload (mantiene la semantica best-effort).
        logJson(">> feedback request", new AiFeedbackRequest(studentId, originalText, selectedSuggestion, accepted));
    }

    private void logJson(String label, Object payload) {
        try {
            log.info("[AI-STUB] {} {}", label, objectMapper.writeValueAsString(payload));
        } catch (RuntimeException exception) {
            // El logging nunca debe romper la llamada: si la serializacion falla, registramos sin formato.
            log.info("[AI-STUB] {} {}", label, payload);
        }
    }
}
