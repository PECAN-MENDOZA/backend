package com.mvp.backend.consent.domain.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mvp.backend.consent.domain.model.PrivacyConsent;

public interface PrivacyConsentRepository extends JpaRepository<PrivacyConsent, UUID> {
}
