package com.mvp.backend.insights.domain;

/**
 * Desenlace de una correccion, derivado de accepted_correction, was_edited y feedback_reason; NO_CHANGES
 * cuando la IA no ofrecio ningun texto distinto del original ("Ya estaba bien"), sea cual sea la respuesta.
 */
public enum Outcome {
    EDITED("Aceptó y editó"),
    ACCEPTED("Aceptó"),
    REJECTED("Rechazó"),
    UNDONE("Deshizo"),
    UNANSWERED("Sin respuesta"),
    NO_CHANGES("Ya estaba bien");

    private final String label;

    Outcome(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Outcome of(Boolean accepted, boolean wasEdited, String feedbackReason) {
        return of(accepted, wasEdited, feedbackReason, false);
    }

    public static Outcome of(Boolean accepted, boolean wasEdited, String feedbackReason, boolean noChanges) {
        if (noChanges) {
            return NO_CHANGES;
        }
        if (accepted == null) {
            return UNANSWERED;
        }
        if (accepted) {
            return wasEdited ? EDITED : ACCEPTED;
        }
        return "UNDO".equals(feedbackReason) ? UNDONE : REJECTED;
    }
}
