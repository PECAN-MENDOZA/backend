package com.mvp.backend.shared.persistence;

import java.util.Locale;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/** Reconoce que restriccion de integridad fue violada, para traducir solo las esperadas a errores de negocio. */
public final class ConstraintViolations {

    private ConstraintViolations() {
    }

    /**
     * Primero por el nombre que expone Hibernate ({@link ConstraintViolationException#getConstraintName()},
     * sin distinguir mayusculas) y, si el driver no lo informa (o lo decora), por el texto de la causa
     * mas especifica.
     */
    public static boolean violates(DataIntegrityViolationException e, String constraint) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve && constraint.equalsIgnoreCase(cve.getConstraintName())) {
                return true;
            }
        }
        String message = String.valueOf(e.getMostSpecificCause().getMessage()).toLowerCase(Locale.ROOT);
        return message.contains(constraint);
    }
}
