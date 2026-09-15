package com.mvp.backend.experiment.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Codigo de acceso de un solo uso: ocho caracteres de un alfabeto sin ambiguedades (sin 0/O, 1/I).
 * Solo se persiste el SHA-256 en hexadecimal minuscula; el texto plano se muestra una unica vez.
 */
public final class AccessCode {

    public static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    public static final int LENGTH = 8;
    public static final String PATTERN = "[A-HJ-NP-Z2-9]{8}";

    private AccessCode() {
    }

    public static String generate(SecureRandom random) {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /** Normaliza (sin espacios, mayusculas) y devuelve el SHA-256 hex en minusculas. */
    public static String hash(String code) {
        String normalized = code.strip().toUpperCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
