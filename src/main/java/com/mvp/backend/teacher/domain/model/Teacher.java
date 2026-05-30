package com.mvp.backend.teacher.domain.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "teacher_users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Teacher {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 80)
    private String username;

    @Column(nullable = false, unique = true, length = 160)
    private String email;

    @Column(length = 30)
    private String phone;

    @Column(nullable = false, length = 120)
    private String institution;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Teacher(String username, String email, String phone, String institution, String passwordHash) {
        this.id = UUID.randomUUID();
        this.username = username;
        this.email = email;
        this.phone = phone;
        this.institution = institution;
        this.passwordHash = passwordHash;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
