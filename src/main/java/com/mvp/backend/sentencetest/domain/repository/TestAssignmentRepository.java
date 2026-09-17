package com.mvp.backend.sentencetest.domain.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mvp.backend.sentencetest.domain.model.TestAssignment;

public interface TestAssignmentRepository extends JpaRepository<TestAssignment, UUID> {
    List<TestAssignment> findByStudentIdOrderByAssignedAtDesc(UUID studentId);
    List<TestAssignment> findByTestIdOrderByAssignedAtAsc(UUID testId);
    boolean existsByTestIdAndStudentId(UUID testId, UUID studentId);
}
