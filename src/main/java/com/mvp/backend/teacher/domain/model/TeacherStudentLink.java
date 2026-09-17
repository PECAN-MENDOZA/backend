package com.mvp.backend.teacher.domain.model;

import java.time.Instant;
import java.util.UUID;

import com.mvp.backend.student.domain.model.Student;

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
        name = "teacher_student_links",
        uniqueConstraints = @UniqueConstraint(name = "uk_teacher_student", columnNames = {"teacher_id", "student_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TeacherStudentLink {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "teacher_id", nullable = false)
    private Teacher teacher;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "classroom_id", nullable = false)
    private Classroom classroom;

    @Column(name = "encrypted_student_real_name", nullable = false)
    private String encryptedStudentRealName;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "last_access_at")
    private Instant lastAccessAt;

    public TeacherStudentLink(
            Teacher teacher,
            Student student,
            Classroom classroom,
            String encryptedStudentRealName,
            String notes) {
        this.id = UUID.randomUUID();
        this.teacher = teacher;
        this.student = student;
        this.classroom = classroom;
        this.encryptedStudentRealName = encryptedStudentRealName;
        this.notes = notes;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public void registerAccess() {
        this.lastAccessAt = Instant.now();
    }

    public void moveTo(Classroom target) {
        this.classroom = target;
    }

    public void updateDetails(String encryptedStudentRealName, String notes) {
        this.encryptedStudentRealName = encryptedStudentRealName;
        this.notes = notes;
    }

    public void deactivate() {
        if (deletedAt == null) {
            deletedAt = Instant.now();
        }
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
