package com.mvp.backend.research.domain.model;

import com.mvp.backend.experiment.domain.model.ExperimentCondition;

/**
 * Orden contrabalanceado de condiciones de un participante. Se asigna una sola vez a partir de su
 * número correlativo dentro del estudio y se persiste: nunca se recalcula a partir de conteos.
 */
public enum ParticipantSequence {
    ASSISTED_FIRST(ExperimentCondition.ASSISTED, ExperimentCondition.UNASSISTED),
    UNASSISTED_FIRST(ExperimentCondition.UNASSISTED, ExperimentCondition.ASSISTED);

    private final ExperimentCondition[] order;

    ParticipantSequence(ExperimentCondition... order) {
        this.order = order;
    }

    /** Impares: ASSISTED_FIRST; pares: UNASSISTED_FIRST. */
    public static ParticipantSequence forParticipantNumber(int participantNumber) {
        if (participantNumber < 1) {
            throw new IllegalArgumentException("Participant number must be positive");
        }
        return participantNumber % 2 == 1 ? ASSISTED_FIRST : UNASSISTED_FIRST;
    }

    public int length() {
        return order.length;
    }

    /** Condición en la posición dada (0 = primera ejecución). */
    public ExperimentCondition conditionAt(int index) {
        if (index < 0 || index >= order.length) {
            throw new IllegalStateException("Participant has already completed every condition");
        }
        return order[index];
    }
}
