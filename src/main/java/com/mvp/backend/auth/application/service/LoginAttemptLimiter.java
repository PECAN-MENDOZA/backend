package com.mvp.backend.auth.application.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.mvp.backend.shared.exception.TooManyRequestsException;

/**
 * Limite de intentos de login en memoria, por clave (alias del alumno o correo, en minusculas):
 * {@value #MAX_FAILURES} fallos dentro de {@link #WINDOW} bloquean esa clave durante {@link #LOCK}.
 * Mientras esta bloqueada responde 429 aunque la credencial sea correcta; un login correcto reinicia
 * el contador. Es por instancia (no compartido entre replicas), suficiente para la escala de la tesis.
 */
@Component
public class LoginAttemptLimiter {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final Duration LOCK = Duration.ofMinutes(15);
    static final String MESSAGE = "Too many login attempts";
    // Por encima de este tamano se descartan las claves sin fallos recientes ni bloqueo vigente.
    private static final int PRUNE_THRESHOLD = 10_000;

    private final Clock clock;
    private final ConcurrentHashMap<String, Attempts> attempts = new ConcurrentHashMap<>();

    public LoginAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    public static String studentKey(String alias) {
        return "student:" + normalize(alias);
    }

    /** Misma clave para /staff/login y /teachers/login: es la misma cuenta. */
    public static String staffKey(String email) {
        return "staff:" + normalize(email);
    }

    /** 429 si la clave esta bloqueada; se llama antes de comprobar la credencial. */
    public void checkAllowed(String key) {
        Attempts current = attempts.get(key);
        if (current != null && current.lockedAt(clock.instant())) {
            throw new TooManyRequestsException(MESSAGE);
        }
    }

    public void recordFailure(String key) {
        Instant now = clock.instant();
        attempts.compute(key, (ignored, current) -> (current == null ? Attempts.NONE : current).withFailure(now));
        if (attempts.size() > PRUNE_THRESHOLD) {
            attempts.entrySet().removeIf(entry -> entry.getValue().expiredAt(now));
        }
    }

    public void recordSuccess(String key) {
        attempts.remove(key);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** Estado inmutable de una clave: fallos dentro de la ventana y fin del bloqueo (si lo hay). */
    private record Attempts(List<Instant> failures, Instant lockedUntil) {

        static final Attempts NONE = new Attempts(List.of(), null);

        boolean lockedAt(Instant now) {
            return lockedUntil != null && now.isBefore(lockedUntil);
        }

        Attempts withFailure(Instant now) {
            if (lockedAt(now)) {
                return this;
            }
            Instant windowStart = now.minus(WINDOW);
            List<Instant> recent = new ArrayList<>();
            for (Instant failure : failures) {
                if (failure.isAfter(windowStart)) {
                    recent.add(failure);
                }
            }
            recent.add(now);
            if (recent.size() >= MAX_FAILURES) {
                return new Attempts(List.of(), now.plus(LOCK));
            }
            return new Attempts(List.copyOf(recent), null);
        }

        boolean expiredAt(Instant now) {
            Instant windowStart = now.minus(WINDOW);
            return !lockedAt(now) && failures.stream().noneMatch(failure -> failure.isAfter(windowStart));
        }
    }
}
