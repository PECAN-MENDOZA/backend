package com.mvp.backend.research.domain.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "research_studies")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ResearchStudy {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(nullable = false, length = 160)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StudyStatus status;

    @Column(name = "next_participant_number", nullable = false)
    private int nextParticipantNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private Researcher createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public ResearchStudy(String code, String title, Researcher createdBy) {
        this.id = UUID.randomUUID();
        this.code = code;
        this.title = title;
        this.status = StudyStatus.DRAFT;
        this.nextParticipantNumber = 1;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public void activate() {
        if (status != StudyStatus.DRAFT) {
            throw new IllegalStateException("Only a draft study can be activated");
        }
        status = StudyStatus.ACTIVE;
    }

    public void close() {
        if (status != StudyStatus.ACTIVE) {
            throw new IllegalStateException("Only an active study can be closed");
        }
        status = StudyStatus.CLOSED;
    }

    public boolean isActive() {
        return status == StudyStatus.ACTIVE;
    }

    /**
     * Reserva el siguiente numero correlativo de participante. Debe invocarse sobre una fila
     * bloqueada ({@code findOwnedForUpdate}) para que dos peticiones no reciban el mismo numero.
     */
    public int allocateParticipantNumber() {
        if (status != StudyStatus.ACTIVE) {
            throw new IllegalStateException("Participants can only be enrolled in an active study");
        }
        return nextParticipantNumber++;
    }
}
