package com.mvp.backend.insights.application.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.mvp.backend.shared.exception.ForbiddenException;
import com.mvp.backend.shared.security.PersonalDataCipher;
import com.mvp.backend.teacher.domain.model.Classroom;
import com.mvp.backend.teacher.domain.model.TeacherStudentLink;
import com.mvp.backend.teacher.domain.repository.ClassroomRepository;
import com.mvp.backend.teacher.domain.repository.TeacherStudentLinkRepository;

/**
 * Comprueba que el docente sea dueno del salon o tenga vinculo activo con el alumno (403 si no)
 * y descifra el nombre real solo a traves de ese vinculo.
 */
@Component
public class TeacherAccess {

    private final ClassroomRepository classroomRepository;
    private final TeacherStudentLinkRepository linkRepository;
    private final PersonalDataCipher personalDataCipher;

    public TeacherAccess(
            ClassroomRepository classroomRepository,
            TeacherStudentLinkRepository linkRepository,
            PersonalDataCipher personalDataCipher) {
        this.classroomRepository = classroomRepository;
        this.linkRepository = linkRepository;
        this.personalDataCipher = personalDataCipher;
    }

    public Classroom classroom(UUID teacherId, UUID classroomId) {
        return classroomRepository.findByIdAndTeacherId(classroomId, teacherId)
                .orElseThrow(() -> new ForbiddenException("Teacher does not own this classroom"));
    }

    public TeacherStudentLink link(UUID teacherId, UUID studentId) {
        return linkRepository.findByTeacherIdAndStudentIdAndDeletedAtIsNull(teacherId, studentId)
                .orElseThrow(() -> new ForbiddenException("Teacher does not have access to this student"));
    }

    /** Vinculos activos del salon en orden de creacion, con el alumno cargado; se llama tras comprobar la propiedad del salon. */
    public List<TeacherStudentLink> activeLinks(UUID classroomId) {
        return linkRepository.findActiveWithStudentByClassroomId(classroomId);
    }

    /** Vinculos activos del docente en todos sus salones, con alumno y salon cargados. */
    public List<TeacherStudentLink> activeLinksOfTeacher(UUID teacherId) {
        return linkRepository.findActiveWithStudentAndClassroomByTeacherId(teacherId);
    }

    public List<UUID> activeStudentIds(UUID classroomId) {
        return activeLinks(classroomId).stream().map(link -> link.getStudent().getId()).toList();
    }

    public String realName(TeacherStudentLink link) {
        return personalDataCipher.decrypt(link.getEncryptedStudentRealName());
    }
}
