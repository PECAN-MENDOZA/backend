package com.mvp.backend.consent.domain.model;

import java.time.Instant;
import java.util.UUID;

import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.teacher.domain.model.Teacher;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "privacy_consents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PrivacyConsent {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "teacher_id")
    private Teacher teacher;

    @Column(name = "consent_type", nullable = false, length = 80)
    private String consentType;

    @Column(nullable = false)
    private boolean accepted;

    @Column(name = "document_version", nullable = false, length = 40)
    private String documentVersion;

    @Column(name = "accepted_at", nullable = false)
    private Instant acceptedAt;

    @Column(name = "user_ip", length = 64)
    private String userIp;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    private PrivacyConsent(
            Student student,
            Teacher teacher,
            String consentType,
            boolean accepted,
            String documentVersion,
            String userIp,
            String userAgent) {
        this.id = UUID.randomUUID();
        this.student = student;
        this.teacher = teacher;
        this.consentType = consentType;
        this.accepted = accepted;
        this.documentVersion = documentVersion;
        this.userIp = userIp;
        this.userAgent = userAgent;
    }

    public static PrivacyConsent forStudent(
            Student student, String consentType, boolean accepted, String documentVersion, String userIp, String userAgent) {
        return new PrivacyConsent(student, null, consentType, accepted, documentVersion, userIp, userAgent);
    }

    public static PrivacyConsent forTeacher(
            Teacher teacher, String consentType, boolean accepted, String documentVersion, String userIp, String userAgent) {
        return new PrivacyConsent(null, teacher, consentType, accepted, documentVersion, userIp, userAgent);
    }

    @PrePersist
    void onCreate() {
        if (acceptedAt == null) {
            acceptedAt = Instant.now();
        }
    }
}
