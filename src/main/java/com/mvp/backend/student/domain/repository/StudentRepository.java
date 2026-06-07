package com.mvp.backend.student.domain.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import com.mvp.backend.student.domain.model.Student;

public interface StudentRepository extends JpaRepository<Student, UUID> {

    Optional<Student> findByUsername(String username);

    boolean existsByUsername(String username);
}
