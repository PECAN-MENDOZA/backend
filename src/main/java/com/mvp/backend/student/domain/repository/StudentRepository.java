package com.mvp.backend.student.domain.repository;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mvp.backend.student.domain.model.Student;

public interface StudentRepository extends JpaRepository<Student, UUID> {

    Optional<Student> findByUsername(String username);

    boolean existsByUsername(String username);

    /** Bloqueo de fila para serializar el arranque de intentos por alumno. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Student s where s.id = :id")
    Optional<Student> findByIdForUpdate(@Param("id") UUID id);
}
