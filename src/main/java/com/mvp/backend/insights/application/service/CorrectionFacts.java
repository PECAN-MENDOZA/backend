package com.mvp.backend.insights.application.service;

import java.util.Collection;
import java.util.EnumMap;

import com.mvp.backend.correction.domain.model.CorrectionSession;
import com.mvp.backend.insights.domain.Outcome;

/** Lecturas de una sesion de correccion que comparten las consultas del panel docente. */
final class CorrectionFacts {

    private CorrectionFacts() {
    }

    static Outcome outcome(CorrectionSession session) {
        return Outcome.of(session.getAcceptedCorrection(), session.isWasEdited(), session.getFeedbackReason());
    }

    /** Texto que quedo escrito: final_text si existe; si no, la sugerencia elegida cuando la acepto; si no, el original. */
    static String finalText(CorrectionSession session) {
        if (session.getFinalText() != null) {
            return session.getFinalText();
        }
        if (Boolean.TRUE.equals(session.getAcceptedCorrection()) && session.getSelectedSuggestion() != null) {
            return session.getSelectedSuggestion();
        }
        return session.getOriginalText();
    }

    /** Condicion de la oracion de prueba (ASSISTED/UNASSISTED) o null fuera de una prueba. Lectura LAZY: dentro de la transaccion. */
    static String assistance(CorrectionSession session) {
        return session.isInTest() ? session.getTestResponse().getSentence().getAssistance().name() : null;
    }

    /** Codigo de la prueba en la que se pidio la correccion, o null fuera de una prueba. */
    static String testCode(CorrectionSession session) {
        return session.isInTest() ? session.getTestResponse().getAttempt().getTest().getCode() : null;
    }

    static EnumMap<Outcome, Long> countOutcomes(Collection<CorrectionSession> sessions) {
        EnumMap<Outcome, Long> counts = new EnumMap<>(Outcome.class);
        for (CorrectionSession session : sessions) {
            counts.merge(outcome(session), 1L, Long::sum);
        }
        return counts;
    }

    /** Limite de una lista: el valor por defecto si falta; acotado a [1, max]. */
    static int clampLimit(Integer limit, int defaultLimit, int maxLimit) {
        if (limit == null) {
            return defaultLimit;
        }
        return Math.max(1, Math.min(maxLimit, limit));
    }
}
