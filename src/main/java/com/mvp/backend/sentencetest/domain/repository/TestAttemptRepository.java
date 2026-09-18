package com.mvp.backend.sentencetest.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mvp.backend.sentencetest.domain.model.AttemptStatus;
import com.mvp.backend.sentencetest.domain.model.TestAttempt;

public interface TestAttemptRepository extends JpaRepository<TestAttempt, UUID> {
    Optional<TestAttempt> findByStudentIdAndStatus(UUID studentId, AttemptStatus status);
    Optional<TestAttempt> findByTestIdAndStudentIdAndStatus(UUID testId, UUID studentId, AttemptStatus status);
    List<TestAttempt> findByTestIdOrderByStartedAtAsc(UUID testId);
    List<TestAttempt> findByStudentIdOrderByStartedAtDesc(UUID studentId);
    boolean existsByStudentIdAndStatus(UUID studentId, AttemptStatus status);
    long countByTestIdAndStatus(UUID testId, AttemptStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from TestAttempt a where a.id = :id")
    Optional<TestAttempt> findByIdForUpdate(@Param("id") UUID id);

    // Intentos de un grupo de alumnos en un estado, con la prueba cargada (prueba en curso del panel docente).
    @Query("select a from TestAttempt a join fetch a.test where a.student.id in :studentIds and a.status = :status")
    List<TestAttempt> findByStudentIdInAndStatus(
            @Param("studentIds") List<UUID> studentIds, @Param("status") AttemptStatus status);

    // Intentos de un alumno en un estado, los terminados mas recientemente primero, con la prueba cargada.
    @Query("""
            select a from TestAttempt a join fetch a.test
            where a.student.id = :studentId and a.status = :status
            order by a.completedAt desc
            """)
    List<TestAttempt> findByStudentIdAndStatusOrderByCompletedAtDesc(
            @Param("studentId") UUID studentId, @Param("status") AttemptStatus status);
}
