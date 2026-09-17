package com.mvp.backend.research.application.service;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

/** Contrasena temporal de 10 caracteres sin simbolos ambiguos (0/O, 1/l/I). */
@Component
public class TemporaryPasswordGenerator {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final int LENGTH = 10;

    private final SecureRandom secureRandom = new SecureRandom();

    public String next() {
        StringBuilder builder = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            builder.append(ALPHABET.charAt(secureRandom.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }
}
