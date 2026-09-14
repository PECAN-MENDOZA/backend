package com.mvp.backend.research.domain.model;

import java.time.Instant;
import java.util.UUID;

import com.mvp.backend.experiment.domain.model.ExperimentCondition;
import com.mvp.backend.student.domain.model.Student;

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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "study_participants",
        uniqueConstraints = {
            @UniqueConstraint(name = "uk_study_pseudonym", columnNames = {"study_id", "pseudonym"}),
            @UniqueConstraint(name = "uk_study_student", columnNames = {"study_id", "student_id"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudyParticipant {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "study_id", nullable = false, updatable = false)
    private ResearchStudy study;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private Student student;

    @Column(nullable = false, length = 20, updatable = false)
    private String pseudonym;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private ParticipantSequence sequence;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Crea el participante {@code P-nnn}; la secuencia se deriva del numero asignado y queda fijada. */
    public StudyParticipant(ResearchStudy study, int participantNumber) {
        this.id = UUID.randomUUID();
        this.study = study;
        this.pseudonym = pseudonymFor(participantNumber);
        this.sequence = ParticipantSequence.forParticipantNumber(participantNumber);
    }

    public static String pseudonymFor(int participantNumber) {
        if (participantNumber < 1) {
            throw new IllegalArgumentException("Participant number must be positive");
        }
        return String.format("P-%03d", participantNumber);
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** Vincula la cuenta de alumno al canjear el primer codigo; idempotente para el mismo alumno. */
    public void linkStudent(Student student) {
        if (this.student != null && !this.student.getId().equals(student.getId())) {
            throw new IllegalStateException("Participant is already linked to another student");
        }
        this.student = student;
    }

    public boolean isLinkedTo(UUID studentId) {
        return student != null && student.getId().equals(studentId);
    }

    /**
     * Condicion de la siguiente ejecucion segun la secuencia asignada y cuantas ejecuciones ya
     * completo ({@code ExperimentRunRepository.countByParticipantIdAndStatus(id, COMPLETED)}).
     * La secuencia persistida nunca se recalcula a partir de conteos.
     */
    public ExperimentCondition nextCondition(long completedRuns) {
        return sequence.conditionAt(Math.toIntExact(completedRuns));
    }

    public boolean hasCompletedProtocol(long completedRuns) {
        return completedRuns >= sequence.length();
    }
}
