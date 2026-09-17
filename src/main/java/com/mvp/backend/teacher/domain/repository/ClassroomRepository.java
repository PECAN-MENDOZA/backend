package com.mvp.backend.teacher.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mvp.backend.teacher.domain.model.Classroom;

public interface ClassroomRepository extends JpaRepository<Classroom, UUID> {

    // Orden explicito en JPQL: NULL first/last depende del motor (H2 los pone primero, Postgres al final),
    // asi que el orden de activos/archivados se fuerza con un CASE en vez de confiar en el orden de NULLs.
    @Query("select c from Classroom c where c.teacher.id = :teacherId "
            + "order by case when c.archivedAt is null then 0 else 1 end, c.createdAt asc")
    List<Classroom> findByTeacherIdOrderByArchivedAtAscCreatedAtAsc(@Param("teacherId") UUID teacherId);

    Optional<Classroom> findByIdAndTeacherId(UUID id, UUID teacherId);

    boolean existsByTeacherIdAndName(UUID teacherId, String name);

    List<Classroom> findAllByOrderByCreatedAtAsc();
}
