package com.mvp.backend.teacher.application.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mvp.backend.shared.exception.BusinessException;
import com.mvp.backend.shared.exception.ConflictException;
import com.mvp.backend.shared.exception.ForbiddenException;
import com.mvp.backend.shared.security.PersonalDataCipher;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.teacher.application.dto.MoveStudentRequest;
import com.mvp.backend.teacher.application.dto.ResetStudentPinResponse;
import com.mvp.backend.teacher.application.dto.StudentLinkResponse;
import com.mvp.backend.teacher.application.dto.UpdateStudentLinkRequest;
import com.mvp.backend.teacher.domain.model.Classroom;
import com.mvp.backend.teacher.domain.model.TeacherStudentLink;
import com.mvp.backend.teacher.domain.repository.ClassroomRepository;
import com.mvp.backend.teacher.domain.repository.TeacherStudentLinkRepository;

@Service
public class TeacherStudentService {

    private final TeacherStudentLinkRepository linkRepository;
    private final ClassroomRepository classroomRepository;
    private final StudentCredentialGenerator credentials;
    private final PersonalDataCipher personalDataCipher;
    private final PasswordEncoder passwordEncoder;

    public TeacherStudentService(
            TeacherStudentLinkRepository linkRepository,
            ClassroomRepository classroomRepository,
            StudentCredentialGenerator credentials,
            PersonalDataCipher personalDataCipher,
            PasswordEncoder passwordEncoder) {
        this.linkRepository = linkRepository;
        this.classroomRepository = classroomRepository;
        this.credentials = credentials;
        this.personalDataCipher = personalDataCipher;
        this.passwordEncoder = passwordEncoder;
    }

    // Listar no cuenta como acceso del alumno: lastAccessAt solo cambia cuando el alumno entra o corrige.
    @Transactional(readOnly = true)
    public List<StudentLinkResponse> listStudents(UUID teacherId) {
        return linkRepository.findByTeacherIdAndDeletedAtIsNullOrderByCreatedAtDesc(teacherId).stream()
                .map(link -> toResponse(link, personalDataCipher))
                .toList();
    }

    /** Alumnos dados de baja por el docente (con deactivatedAt), la baja mas reciente primero. */
    @Transactional(readOnly = true)
    public List<StudentLinkResponse> listInactiveStudents(UUID teacherId) {
        return linkRepository.findByTeacherIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(teacherId).stream()
                .map(link -> toResponse(link, personalDataCipher))
                .toList();
    }

    /** Deshace la baja: 403 si el vinculo no es del docente; 409 si ya esta activo. */
    @Transactional
    public StudentLinkResponse reactivateStudent(UUID teacherId, UUID studentId) {
        TeacherStudentLink link = linkRepository.findByTeacherIdAndStudentId(teacherId, studentId)
                .orElseThrow(() -> new ForbiddenException("Teacher does not have access to this student"));
        if (!link.isDeleted()) {
            throw new ConflictException("Student is already active");
        }
        link.reactivate();
        return toResponse(link, personalDataCipher);
    }

    /** PIN nuevo; credentials_changed_at = ahora cierra las sesiones abiertas del alumno en el teclado. */
    @Transactional
    public ResetStudentPinResponse resetPin(UUID teacherId, UUID studentId) {
        TeacherStudentLink link = ownedLink(teacherId, studentId);
        Student student = link.getStudent();
        String pin = credentials.newPin();
        student.resetCredentials(passwordEncoder.encode(pin), Instant.now());
        return new ResetStudentPinResponse(student.getId(), student.getUsername(), pin);
    }

    @Transactional
    public StudentLinkResponse updateStudent(UUID teacherId, UUID studentId, UpdateStudentLinkRequest request) {
        TeacherStudentLink link = ownedLink(teacherId, studentId);
        link.updateDetails(personalDataCipher.encrypt(request.studentRealName().trim()), request.notes());
        return toResponse(link, personalDataCipher);
    }

    @Transactional
    public StudentLinkResponse moveStudent(UUID teacherId, UUID studentId, MoveStudentRequest request) {
        TeacherStudentLink link = ownedLink(teacherId, studentId);
        Classroom target = classroomRepository.findByIdAndTeacherId(request.classroomId(), teacherId)
                .orElseThrow(() -> new ForbiddenException("Teacher does not have access to this classroom"));
        if (target.isArchived()) {
            throw new BusinessException("Cannot move a student to an archived classroom");
        }
        link.moveTo(target);
        return toResponse(link, personalDataCipher);
    }

    @Transactional
    public void deactivateStudent(UUID teacherId, UUID studentId) {
        ownedLink(teacherId, studentId).deactivate();
    }

    private TeacherStudentLink ownedLink(UUID teacherId, UUID studentId) {
        return linkRepository.findByTeacherIdAndStudentIdAndDeletedAtIsNull(teacherId, studentId)
                .orElseThrow(() -> new ForbiddenException("Teacher does not have access to this student"));
    }

    static StudentLinkResponse toResponse(TeacherStudentLink link, PersonalDataCipher cipher) {
        return new StudentLinkResponse(
                link.getId(),
                link.getClassroom().getId(),
                link.getClassroom().getName(),
                link.getStudent().getId(),
                link.getStudent().getUsername(),
                cipher.decrypt(link.getEncryptedStudentRealName()),
                link.getNotes(),
                link.getCreatedAt(),
                link.getLastAccessAt(),
                link.getDeletedAt());
    }
}
