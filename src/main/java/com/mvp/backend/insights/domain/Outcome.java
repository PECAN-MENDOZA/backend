package com.mvp.backend.insights.domain;

/** Desenlace de una correccion, derivado de accepted_correction, was_edited y feedback_reason. */
public enum Outcome {
    EDITED("Resolvió solo"),
    ACCEPTED("Aceptó"),
    REJECTED("Rechazó"),
    UNDONE("Deshizo"),
    UNANSWERED("Sin respuesta");

    private final String label;

    Outcome(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Outcome of(Boolean accepted, boolean wasEdited, String feedbackReason) {
        if (accepted == null) {
            return UNANSWERED;
        }
        if (accepted) {
            return wasEdited ? EDITED : ACCEPTED;
        }
        return "UNDO".equals(feedbackReason) ? UNDONE : REJECTED;
    }
}
