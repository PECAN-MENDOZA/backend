package com.mvp.backend.sentencetest.application.dto;

/**
 * Diferencia pareada con ayuda - sin ayuda sobre los alumnos que tienen ambas condiciones.
 * bootstrapLower/Upper: bootstrap percentil sobre las diferencias por alumno; tLower/tUpper, t, p y dz:
 * resumen t pareado (solo con n >= 2 y varianza no nula).
 */
public record PairedDelta(
        int n,
        Double meanAssisted,
        Double meanUnassisted,
        Double meanDelta,
        Double bootstrapLower,
        Double bootstrapUpper,
        Double tLower,
        Double tUpper,
        Double t,
        Double p,
        Double dz) {

    public static final PairedDelta EMPTY = new PairedDelta(0, null, null, null, null, null, null, null, null, null, null);
}
