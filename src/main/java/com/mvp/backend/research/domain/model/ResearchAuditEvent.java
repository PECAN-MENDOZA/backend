package com.mvp.backend.research.domain.model;

import java.time.Instant;
import java.util.UUID;

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
@Table(name = "research_audit_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ResearchAuditEvent {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "researcher_id", nullable = false, updatable = false)
    private Researcher researcher;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "study_id", updatable = false)
    private ResearchStudy study;

    @Column(nullable = false, length = 80, updatable = false)
    private String action;

    @Column(name = "target_id", updatable = false)
    private UUID targetId;

    @Column(columnDefinition = "TEXT", updatable = false)
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public ResearchAuditEvent(Researcher researcher, ResearchStudy study, String action, UUID targetId, String detail) {
        this.id = UUID.randomUUID();
        this.researcher = researcher;
        this.study = study;
        this.action = action;
        this.targetId = targetId;
        this.detail = detail;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
