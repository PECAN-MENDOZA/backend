package com.mvp.backend.auth.application.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.mvp.backend.shared.exception.TooManyRequestsException;

class LoginAttemptLimiterTests {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-15T15:00:00Z"));
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(clock);

    @Test
    void fiveFailuresLockTheKeyForFifteenMinutes() {
        String key = LoginAttemptLimiter.studentKey("Tigre-07");
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure(key);
        }
        assertThatCode(() -> limiter.checkAllowed(key)).doesNotThrowAnyException();

        limiter.recordFailure(key);
        assertThatThrownBy(() -> limiter.checkAllowed(key))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessage("Too many login attempts");
        // La clave se normaliza: el mismo alias en otra capitalizacion tambien esta bloqueado.
        assertThatThrownBy(() -> limiter.checkAllowed(LoginAttemptLimiter.studentKey(" tigre-07 ")))
                .isInstanceOf(TooManyRequestsException.class);

        clock.advance(Duration.ofMinutes(14));
        assertThatThrownBy(() -> limiter.checkAllowed(key)).isInstanceOf(TooManyRequestsException.class);
        clock.advance(Duration.ofMinutes(1));
        assertThatCode(() -> limiter.checkAllowed(key)).doesNotThrowAnyException();
        // Tras el bloqueo empieza de cero: un fallo mas no vuelve a bloquear.
        limiter.recordFailure(key);
        assertThatCode(() -> limiter.checkAllowed(key)).doesNotThrowAnyException();
    }

    @Test
    void failuresOutsideTheWindowDoNotCount() {
        String key = LoginAttemptLimiter.staffKey("Ana@Colegio.edu");
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure(key);
        }
        clock.advance(Duration.ofMinutes(16));
        limiter.recordFailure(key);
        assertThatCode(() -> limiter.checkAllowed(key)).doesNotThrowAnyException();
    }

    @Test
    void successResetsTheCounterAndKeysAreIndependent() {
        String key = LoginAttemptLimiter.staffKey("ana@colegio.edu");
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure(key);
        }
        limiter.recordSuccess(key);
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure(key);
        }
        assertThatCode(() -> limiter.checkAllowed(key)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.checkAllowed(LoginAttemptLimiter.staffKey("otra@colegio.edu")))
                .doesNotThrowAnyException();
    }
}
