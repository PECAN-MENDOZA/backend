package com.mvp.backend.consent.application.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mvp.backend.auth.domain.model.UserRole;
import com.mvp.backend.consent.application.dto.CreatePrivacyConsentRequest;
import com.mvp.backend.consent.application.dto.PrivacyConsentResponse;
import com.mvp.backend.consent.domain.model.PrivacyConsent;
import com.mvp.backend.consent.domain.repository.PrivacyConsentRepository;
import com.mvp.backend.shared.exception.NotFoundException;
import com.mvp.backend.student.domain.repository.StudentRepository;
import com.mvp.backend.teacher.domain.repository.TeacherRepository;

@Service
public class PrivacyConsentService {

    private final PrivacyConsentRepository consentRepository;
    private final StudentRepository studentRepository;
    private final TeacherRepository teacherRepository;

    public PrivacyConsentService(
            PrivacyConsentRepository consentRepository,
            StudentRepository studentRepository,
            TeacherRepository teacherRepository) {
        this.consentRepository = consentRepository;
        this.studentRepository = studentRepository;
        this.teacherRepository = teacherRepository;
    }

    @Transactional
    public PrivacyConsentResponse create(
            UUID userId,
            UserRole role,
            CreatePrivacyConsentRequest request,
            String ipAddress,
            String userAgent) {
        PrivacyConsent consent = switch (role) {
            case STUDENT -> PrivacyConsent.forStudent(
                    studentRepository.findById(userId).orElseThrow(() -> new NotFoundException("Student not found")),
                    request.consentType(),
                    request.accepted(),
                    request.documentVersion(),
                    ipAddress,
                    userAgent);
            case TEACHER -> PrivacyConsent.forTeacher(
                    teacherRepository.findById(userId).orElseThrow(() -> new NotFoundException("Teacher not found")),
                    request.consentType(),
                    request.accepted(),
                    request.documentVersion(),
                    ipAddress,
                    userAgent);
        };
        PrivacyConsent saved = consentRepository.save(consent);
        return new PrivacyConsentResponse(
                saved.getId(),
                saved.getConsentType(),
                saved.isAccepted(),
                saved.getDocumentVersion(),
                saved.getAcceptedAt());
    }
}
