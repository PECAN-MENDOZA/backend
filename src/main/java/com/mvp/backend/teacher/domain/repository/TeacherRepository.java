package com.mvp.backend.teacher.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mvp.backend.teacher.domain.model.Teacher;

public interface TeacherRepository extends JpaRepository<Teacher, UUID> {

    List<Teacher> findAllByOrderByCreatedAtAsc();

    Optional<Teacher> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByUsername(String username);
}
