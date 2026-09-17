package com.mvp.backend.teacher.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mvp.backend.teacher.domain.model.Classroom;

public interface ClassroomRepository extends JpaRepository<Classroom, UUID> {

    List<Classroom> findByTeacherIdOrderByArchivedAtAscCreatedAtAsc(UUID teacherId);

    Optional<Classroom> findByIdAndTeacherId(UUID id, UUID teacherId);

    boolean existsByTeacherIdAndName(UUID teacherId, String name);

    List<Classroom> findAllByOrderByCreatedAtAsc();
}
