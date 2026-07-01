package com.mvp.backend.correction.domain.model;

public enum ErrorType {

    TILDE("Tildes y acentuación"),
    CONFUSION_B_V("Confusión b / v"),
    H_MUDA("H muda"),
    C_Q_K("Sonido fuerte c / q / k"),
    LETRA_DOBLE("Consonantes dobles"),
    DISLEXIA_VISUAL("Inversión de letras (b/d, p/q)"),
    OTRO("Otro");

    private final String label;

    ErrorType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
