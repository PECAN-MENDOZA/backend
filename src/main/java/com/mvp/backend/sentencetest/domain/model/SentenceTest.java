package com.mvp.backend.sentencetest.domain.model;

import java.time.Instant;
import java.util.UUID;

import com.mvp.backend.shared.exception.BusinessException;
import com.mvp.backend.shared.exception.ConflictException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "sentence_tests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SentenceTest {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(nullable = false, length = 120)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TestStatus status;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    public SentenceTest(String code, String title, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.code = code;
        this.title = title;
        this.createdBy = createdBy;
        this.status = TestStatus.DRAFT;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** Solo un borrador admite cambios de titulo, notas u oraciones. */
    public void requireEditable() {
        if (status != TestStatus.DRAFT) {
            throw new ConflictException("Test is not editable once activated");
        }
    }

    public void updateDetails(String title, String notes) {
        requireEditable();
        this.title = title;
        this.notes = notes;
    }

    public void activate(int sentenceCount, Instant now) {
        if (status != TestStatus.DRAFT) {
            throw new ConflictException("Test is not a draft");
        }
        if (sentenceCount < 1) {
            throw new BusinessException("Test needs at least one sentence");
        }
        this.status = TestStatus.ACTIVE;
        this.activatedAt = now;
    }

    public void close(Instant now) {
        if (status != TestStatus.ACTIVE) {
            throw new ConflictException("Only an active test can be closed");
        }
        this.status = TestStatus.CLOSED;
        this.closedAt = now;
    }

    public boolean isActive() {
        return status == TestStatus.ACTIVE;
    }
}
