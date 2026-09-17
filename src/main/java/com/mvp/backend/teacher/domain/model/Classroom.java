package com.mvp.backend.teacher.domain.model;

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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "classrooms",
        uniqueConstraints = @UniqueConstraint(name = "uk_classroom_teacher_name", columnNames = {"teacher_id", "name"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Classroom {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "teacher_id", nullable = false)
    private Teacher teacher;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    public Classroom(Teacher teacher, String name) {
        this.id = UUID.randomUUID();
        this.teacher = teacher;
        this.name = name;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public void rename(String newName) {
        this.name = newName;
    }

    public void archive() {
        if (archivedAt == null) {
            archivedAt = Instant.now();
        }
    }

    public void unarchive() {
        archivedAt = null;
    }

    public boolean isArchived() {
        return archivedAt != null;
    }
}
