package com.mvp.backend.research.application.dto;

/** Valor de un participante en cada condicion; la diferencia emparejada es {@code assisted - unassisted}. */
public record PairValue(double assisted, double unassisted) {

    public double delta() {
        return assisted - unassisted;
    }
}
