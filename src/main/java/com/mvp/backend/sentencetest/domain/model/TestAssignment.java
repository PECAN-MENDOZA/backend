package com.mvp.backend.sentencetest.domain.model;

import java.time.Instant;
import java.util.UUID;

import com.mvp.backend.student.domain.model.Student;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "test_assignments", uniqueConstraints = @UniqueConstraint(name = "uk_test_assignment", columnNames = {"test_id", "student_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TestAssignment {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "test_id", nullable = false)
    private SentenceTest test;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @Column(name = "classroom_id")
    private UUID classroomId;

    @Column(name = "assigned_by", nullable = false)
    private UUID assignedBy;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    public TestAssignment(SentenceTest test, Student student, UUID classroomId, UUID assignedBy, Instant assignedAt) {
        this.id = UUID.randomUUID();
        this.test = test;
        this.student = student;
        this.classroomId = classroomId;
        this.assignedBy = assignedBy;
        this.assignedAt = assignedAt;
    }
}
