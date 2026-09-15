package com.mvp.backend.experiment.application.dto;

import jakarta.validation.constraints.NotNull;

/** El alumno solo elige entre motivos fijos: nunca escribe texto libre que llegue al investigador. */
public record CancelExperimentRequest(@NotNull Reason reason) {

    public enum Reason {
        ABANDONED("Cancelled by the student: abandoned the task"),
        TECHNICAL_PROBLEM("Cancelled by the student: technical problem"),
        INTERRUPTED("Cancelled by the student: interrupted");

        private final String description;

        Reason(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }
}
