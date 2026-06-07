package com.mvp.backend.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.mvp.backend.config.AppSecurityProperties;

class PersonalDataCipherTests {

    @Test
    void encryptsAndDecryptsPersonalData() {
        var properties = new AppSecurityProperties(
                "test",
                Duration.ofHours(5),
                "test-secret-that-is-long-enough-for-hs256-signing",
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                java.util.List.of("*"),
                true);
        var cipher = new PersonalDataCipher(properties);

        String encrypted = cipher.encrypt("Juan Perez");

        assertThat(encrypted).isNotEqualTo("Juan Perez");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("Juan Perez");
    }
}
