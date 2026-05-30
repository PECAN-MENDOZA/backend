package com.mvp.backend.teacher.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mvp.backend.teacher.domain.model.TeacherStudentLink;

public interface TeacherStudentLinkRepository extends JpaRepository<TeacherStudentLink, UUID> {

    List<TeacherStudentLink> findByTeacherIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID teacherId);

    Optional<TeacherStudentLink> findByTeacherIdAndStudentIdAndDeletedAtIsNull(UUID teacherId, UUID studentId);

    boolean existsByTeacherIdAndStudentIdAndDeletedAtIsNull(UUID teacherId, UUID studentId);
}
