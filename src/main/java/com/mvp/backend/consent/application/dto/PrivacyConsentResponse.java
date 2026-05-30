package com.mvp.backend.consent.application.dto;

import java.time.Instant;
import java.util.UUID;

public record PrivacyConsentResponse(
        UUID id,
        String consentType,
        boolean accepted,
        String documentVersion,
        Instant acceptedAt) {
}
