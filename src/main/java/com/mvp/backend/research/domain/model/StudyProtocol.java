package com.mvp.backend.research.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "study_protocols",
        uniqueConstraints = @UniqueConstraint(name = "uk_protocol_version", columnNames = {"study_id", "version"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudyProtocol {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "study_id", nullable = false, updatable = false)
    private ResearchStudy study;

    @Column(nullable = false, updatable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProtocolStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "protocol", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ProtocolTask> tasks = new ArrayList<>();

    public StudyProtocol(ResearchStudy study, int version) {
        if (version < 1) {
            throw new IllegalArgumentException("Protocol version must be positive");
        }
        this.id = UUID.randomUUID();
        this.study = study;
        this.version = version;
        this.status = ProtocolStatus.DRAFT;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public List<ProtocolTask> getTasks() {
        return Collections.unmodifiableList(tasks);
    }

    public ProtocolTask addTask(TaskVariant variant, String promptText) {
        if (status != ProtocolStatus.DRAFT) {
            throw new IllegalStateException("Tasks can only be added to a draft protocol");
        }
        if (findTask(variant).isPresent()) {
            throw new IllegalStateException("Protocol already defines " + variant);
        }
        var task = new ProtocolTask(this, variant, promptText);
        tasks.add(task);
        return task;
    }

    public Optional<ProtocolTask> findTask(TaskVariant variant) {
        return tasks.stream().filter(task -> task.getVariant() == variant).findFirst();
    }

    public void activate() {
        if (status != ProtocolStatus.DRAFT) {
            throw new IllegalStateException("Only a draft protocol can be activated");
        }
        for (TaskVariant variant : TaskVariant.values()) {
            if (findTask(variant).isEmpty()) {
                throw new IllegalStateException("Protocol is missing " + variant);
            }
        }
        status = ProtocolStatus.ACTIVE;
    }

    public void retire() {
        if (status != ProtocolStatus.ACTIVE) {
            throw new IllegalStateException("Only an active protocol can be retired");
        }
        status = ProtocolStatus.RETIRED;
    }

    public boolean isActive() {
        return status == ProtocolStatus.ACTIVE;
    }
}
