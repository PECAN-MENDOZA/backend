package com.mvp.backend.consent.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreatePrivacyConsentRequest(
        @NotBlank @Size(max = 80) String consentType,
        boolean accepted,
        @NotBlank @Size(max = 40) String documentVersion) {
}
