package com.mvp.backend.research.application.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mvp.backend.correction.domain.repository.CorrectionSessionRepository;
import com.mvp.backend.research.application.dto.ClassroomDirectoryResponse;
import com.mvp.backend.research.application.dto.CreateTeacherRequest;
import com.mvp.backend.research.application.dto.CreatedTeacherResponse;
import com.mvp.backend.research.application.dto.TeacherSummaryResponse;
import com.mvp.backend.research.application.dto.TemporaryPasswordResponse;
import com.mvp.backend.research.domain.repository.ResearcherRepository;
import com.mvp.backend.shared.exception.ConflictException;
import com.mvp.backend.shared.exception.NotFoundException;
import com.mvp.backend.teacher.domain.model.Classroom;
import com.mvp.backend.teacher.domain.model.Teacher;
import com.mvp.backend.teacher.domain.repository.ClassroomRepository;
import com.mvp.backend.teacher.domain.repository.TeacherRepository;
import com.mvp.backend.teacher.domain.repository.TeacherStudentLinkRepository;

/** Alta de docentes por el investigador y directorio de salones sin identidad de alumnos. */
@Service
public class ResearchTeacherService {

    private final TeacherRepository teacherRepository;
    private final ResearcherRepository researcherRepository;
    private final ClassroomRepository classroomRepository;
    private final TeacherStudentLinkRepository linkRepository;
    private final CorrectionSessionRepository sessionRepository;
    private final TemporaryPasswordGenerator passwords;
    private final PasswordEncoder passwordEncoder;

    public ResearchTeacherService(
            TeacherRepository teacherRepository,
            ResearcherRepository researcherRepository,
            ClassroomRepository classroomRepository,
            TeacherStudentLinkRepository linkRepository,
            CorrectionSessionRepository sessionRepository,
            TemporaryPasswordGenerator passwords,
            PasswordEncoder passwordEncoder) {
        this.teacherRepository = teacherRepository;
        this.researcherRepository = researcherRepository;
        this.classroomRepository = classroomRepository;
        this.linkRepository = linkRepository;
        this.sessionRepository = sessionRepository;
        this.passwords = passwords;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public CreatedTeacherResponse createTeacher(UUID researcherId, CreateTeacherRequest request) {
        String email = request.email().trim();
        if (teacherRepository.existsByEmail(email) || researcherRepository.findByEmail(email).isPresent()) {
            throw new ConflictException("Email is already in use");
        }
        String username = uniqueUsername(email);
        String temporaryPassword = passwords.next();
        Teacher teacher = teacherRepository.save(new Teacher(
                username,
                email,
                null,
                request.institution().trim(),
                passwordEncoder.encode(temporaryPassword),
                researcherId,
                true,
                request.fullName().trim()));
        return new CreatedTeacherResponse(
                teacher.getId(),
                teacher.getUsername(),
                teacher.getFullName(),
                teacher.getEmail(),
                teacher.getInstitution(),
                temporaryPassword);
    }

    @Transactional(readOnly = true)
    public List<TeacherSummaryResponse> listTeachers() {
        return teacherRepository.findAllByOrderByCreatedAtAsc().stream()
                .map(teacher -> new TeacherSummaryResponse(
                        teacher.getId(),
                        teacher.getUsername(),
                        teacher.getFullName(),
                        teacher.getEmail(),
                        teacher.getInstitution(),
                        teacher.getCreatedAt(),
                        teacher.isMustChangePassword(),
                        classroomRepository.findByTeacherIdOrderByArchivedAtAscCreatedAtAsc(teacher.getId()).size(),
                        linkRepository.countByTeacherIdAndDeletedAtIsNull(teacher.getId())))
                .toList();
    }

    @Transactional
    public TemporaryPasswordResponse resetPassword(UUID teacherId) {
        Teacher teacher = teacherRepository.findById(teacherId)
                .orElseThrow(() -> new NotFoundException("Teacher not found"));
        String temporaryPassword = passwords.next();
        teacher.assignTemporaryPassword(passwordEncoder.encode(temporaryPassword));
        return new TemporaryPasswordResponse(teacher.getId(), temporaryPassword);
    }

    @Transactional(readOnly = true)
    public List<ClassroomDirectoryResponse> classroomDirectory() {
        return classroomRepository.findAllByOrderByCreatedAtAsc().stream()
                .map(this::toDirectory)
                .toList();
    }

    private ClassroomDirectoryResponse toDirectory(Classroom classroom) {
        var links = linkRepository.findByClassroomIdAndDeletedAtIsNullOrderByCreatedAtAsc(classroom.getId());
        // Ultima actividad real del alumno (su ultima sesion de correccion), no cuando el docente abrio la lista.
        Map<UUID, Instant> lastActivity = new HashMap<>();
        if (!links.isEmpty()) {
            List<UUID> studentIds = links.stream().map(link -> link.getStudent().getId()).toList();
            for (Object[] row : sessionRepository.lastActivityByStudent(studentIds)) {
                lastActivity.put((UUID) row[0], (Instant) row[1]);
            }
        }
        var students = links.stream()
                .map(link -> new ClassroomDirectoryResponse.StudentEntry(
                        link.getStudent().getId(),
                        link.getStudent().getUsername(),
                        lastActivity.get(link.getStudent().getId())))
                .toList();
        return new ClassroomDirectoryResponse(
                classroom.getId(),
                classroom.getName(),
                classroom.getTeacher().getId(),
                classroom.getTeacher().getUsername(),
                classroom.isArchived(),
                students);
    }

    // teacher_users.username es VARCHAR(80): la parte local se acota a 70 para que quepa el sufijo "-N".
    private static final int USERNAME_BASE_MAX = 70;

    private String uniqueUsername(String email) {
        String base = email.substring(0, email.indexOf('@')).toLowerCase(Locale.ROOT);
        if (base.length() > USERNAME_BASE_MAX) {
            base = base.substring(0, USERNAME_BASE_MAX);
        }
        if (!teacherRepository.existsByUsername(base)) {
            return base;
        }
        for (int suffix = 2; ; suffix++) {
            String candidate = base + "-" + suffix;
            if (!teacherRepository.existsByUsername(candidate)) {
                return candidate;
            }
        }
    }
}
