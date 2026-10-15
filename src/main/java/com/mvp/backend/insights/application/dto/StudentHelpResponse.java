package com.mvp.backend.insights.application.dto;

import java.util.UUID;

/**
 * Como respondio un alumno a la ayuda del teclado en un periodo: conteo por desenlace y porcentaje sobre el
 * total. total cuenta todas las sesiones, incluidas las NO_CHANGES ("Ya estaba bien").
 */
public record StudentHelpResponse(
        UUID studentId,
        String from,
        String to,
        long total,
        long edited,
        long accepted,
        long rejected,
        long undone,
        long unanswered,
        long noChanges,
        double editedPct,
        double acceptedPct,
        double rejectedPct,
        double undonePct,
        double unansweredPct,
        double noChangesPct) {
}
