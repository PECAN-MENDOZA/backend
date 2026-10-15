package com.mvp.backend.teacher.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mvp.backend.teacher.domain.model.TeacherStudentLink;

public interface TeacherStudentLinkRepository extends JpaRepository<TeacherStudentLink, UUID> {

    List<TeacherStudentLink> findByTeacherIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID teacherId);

    Optional<TeacherStudentLink> findByTeacherIdAndStudentIdAndDeletedAtIsNull(UUID teacherId, UUID studentId);

    boolean existsByTeacherIdAndStudentIdAndDeletedAtIsNull(UUID teacherId, UUID studentId);

    // Vinculo del docente con el alumno, activo o dado de baja (reactivar).
    Optional<TeacherStudentLink> findByTeacherIdAndStudentId(UUID teacherId, UUID studentId);

    // Vinculos dados de baja del docente, la baja mas reciente primero.
    List<TeacherStudentLink> findByTeacherIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(UUID teacherId);

    // Vinculos activos del alumno con cualquier docente (acceso y "ultimo acceso").
    List<TeacherStudentLink> findByStudentIdAndDeletedAtIsNull(UUID studentId);

    boolean existsByStudentIdAndDeletedAtIsNull(UUID studentId);

    // Acceso del alumno: vinculo activo con un docente que no fue dado de baja.
    List<TeacherStudentLink> findByStudentIdAndDeletedAtIsNullAndTeacherDeactivatedAtIsNull(UUID studentId);

    boolean existsByStudentIdAndDeletedAtIsNullAndTeacherDeactivatedAtIsNull(UUID studentId);

    List<TeacherStudentLink> findByClassroomIdAndDeletedAtIsNullOrderByCreatedAtAsc(UUID classroomId);

    long countByClassroomIdAndDeletedAtIsNull(UUID classroomId);

    // Vinculos activos del salon con el alumno ya cargado (panel del docente: evita una consulta por alumno).
    @Query("""
            select l from TeacherStudentLink l
            join fetch l.student
            where l.classroom.id = :classroomId and l.deletedAt is null
            order by l.createdAt asc
            """)
    List<TeacherStudentLink> findActiveWithStudentByClassroomId(@Param("classroomId") UUID classroomId);

    // Vinculos activos del docente en todos sus salones, con alumno y salon cargados (prueba en curso).
    @Query("""
            select l from TeacherStudentLink l
            join fetch l.student
            join fetch l.classroom
            where l.teacher.id = :teacherId and l.deletedAt is null
            order by l.createdAt asc
            """)
    List<TeacherStudentLink> findActiveWithStudentAndClassroomByTeacherId(@Param("teacherId") UUID teacherId);

    long countByTeacherIdAndDeletedAtIsNull(UUID teacherId);
}
