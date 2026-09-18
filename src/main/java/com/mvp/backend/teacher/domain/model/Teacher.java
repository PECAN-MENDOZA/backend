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

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "full_name", length = 120)
    private String fullName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Teacher(String username, String email, String phone, String institution, String passwordHash) {
        this(username, email, phone, institution, passwordHash, null, false, null);
    }

    public Teacher(
            String username,
            String email,
            String phone,
            String institution,
            String passwordHash,
            UUID createdBy,
            boolean mustChangePassword,
            String fullName) {
        this.id = UUID.randomUUID();
        this.username = username;
        this.email = email;
        this.phone = phone;
        this.institution = institution;
        this.passwordHash = passwordHash;
        this.createdBy = createdBy;
        this.mustChangePassword = mustChangePassword;
        this.fullName = fullName;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** Contrasena elegida por el docente: deja de exigirse el cambio. */
    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
        this.mustChangePassword = false;
    }

    /** Contrasena temporal asignada por el investigador: se exige cambiarla al entrar. */
    public void assignTemporaryPassword(String passwordHash) {
        this.passwordHash = passwordHash;
        this.mustChangePassword = true;
    }
}
