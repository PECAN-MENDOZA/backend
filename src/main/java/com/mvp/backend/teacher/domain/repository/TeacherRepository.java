package com.mvp.backend.teacher.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mvp.backend.teacher.domain.model.Teacher;

public interface TeacherRepository extends JpaRepository<Teacher, UUID> {

    List<Teacher> findAllByOrderByCreatedAtAsc();

    Optional<Teacher> findByEmail(String email);

    // El correo no distingue mayusculas: login y chequeo de duplicados.
    Optional<Teacher> findByEmailIgnoreCase(String email);

    boolean existsByEmail(String email);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByUsername(String username);
}
