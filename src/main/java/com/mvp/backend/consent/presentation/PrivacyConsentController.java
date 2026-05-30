package com.mvp.backend.consent.presentation;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mvp.backend.auth.domain.model.UserRole;
import com.mvp.backend.consent.application.dto.CreatePrivacyConsentRequest;
import com.mvp.backend.consent.application.dto.PrivacyConsentResponse;
import com.mvp.backend.consent.application.service.PrivacyConsentService;

@RestController
@RequestMapping("/api/v1/consents")
public class PrivacyConsentController {

    private final PrivacyConsentService privacyConsentService;

    public PrivacyConsentController(PrivacyConsentService privacyConsentService) {
        this.privacyConsentService = privacyConsentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PrivacyConsentResponse create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreatePrivacyConsentRequest request,
            HttpServletRequest httpRequest) {
        return privacyConsentService.create(
                UUID.fromString(jwt.getSubject()),
                UserRole.valueOf(jwt.getClaimAsString("role")),
                request,
                httpRequest.getRemoteAddr(),
                httpRequest.getHeader("User-Agent"));
    }
}
