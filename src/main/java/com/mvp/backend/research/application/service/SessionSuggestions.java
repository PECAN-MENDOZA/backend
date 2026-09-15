package com.mvp.backend.research.application.service;

import java.util.List;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import com.mvp.backend.correction.application.service.OfferedSuggestions;
import com.mvp.backend.correction.domain.model.CorrectionSession;

/**
 * Unica definicion, compartida por la anotacion ciega y las metricas, de que sugerencia de una sesion se
 * evalua y cual se considera aceptada.
 *
 * <ul>
 *   <li><b>Evaluada</b>: la que el alumno acepto ({@code selectedSuggestion}) cuando la sesion registro
 *       aceptacion; en cualquier otro caso (rechazo, UNDO, sin feedback) la primera ofrecida, que es la
 *       que el teclado marca como recomendada.</li>
 *   <li><b>Aceptada</b>: el indice de {@code selectedSuggestion} dentro de la lista ofrecida, solo si la
 *       sesion registro {@code acceptedCorrection = true}; {@code -1} en cualquier otro caso.</li>
 * </ul>
 */
final class SessionSuggestions {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private SessionSuggestions() {
    }

    /** Lista ofrecida al alumno (texto corregido primero, alternativas, sin duplicados, maximo 3). */
    static List<String> offered(ObjectMapper objectMapper, CorrectionSession session) {
        List<String> alternatives = List.of();
        if (session.getSuggestionsJson() != null) {
            try {
                alternatives = objectMapper.readValue(session.getSuggestionsJson(), STRING_LIST);
            } catch (JacksonException e) {
                throw new IllegalStateException("Could not deserialize suggestions", e);
            }
        }
        return OfferedSuggestions.of(session.getCorrectedText(), alternatives);
    }

    static int evaluatedIndex(CorrectionSession session, List<String> offered) {
        int accepted = acceptedIndex(session, offered);
        return accepted >= 0 ? accepted : 0;
    }

    static int acceptedIndex(CorrectionSession session, List<String> offered) {
        if (Boolean.TRUE.equals(session.getAcceptedCorrection()) && session.getSelectedSuggestion() != null) {
            return offered.indexOf(session.getSelectedSuggestion());
        }
        return -1;
    }
}
