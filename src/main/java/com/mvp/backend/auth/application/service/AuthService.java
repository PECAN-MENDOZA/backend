package com.mvp.backend.auth.application.service;

import java.util.List;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mvp.backend.auth.application.dto.AuthResponse;
import com.mvp.backend.auth.application.dto.ChangePasswordRequest;
import com.mvp.backend.auth.application.dto.StaffLoginRequest;
import com.mvp.backend.auth.application.dto.StudentLoginRequest;
import com.mvp.backend.auth.application.dto.TeacherLoginRequest;
import com.mvp.backend.auth.domain.model.UserRole;
import com.mvp.backend.research.domain.model.Researcher;
import com.mvp.backend.research.domain.repository.ResearcherRepository;
import com.mvp.backend.shared.exception.BusinessException;
import com.mvp.backend.shared.exception.UnauthorizedException;
import com.mvp.backend.student.domain.repository.StudentRepository;
import com.mvp.backend.teacher.domain.model.Teacher;
import com.mvp.backend.teacher.domain.model.TeacherStudentLink;
import com.mvp.backend.teacher.domain.repository.TeacherRepository;
import com.mvp.backend.teacher.domain.repository.TeacherStudentLinkRepository;

@Service
public class AuthService {

    static final String INVALID_STUDENT = "Invalid student credentials";
    static final String INVALID_TEACHER = "Invalid teacher credentials";
    static final String INVALID_STAFF = "Invalid staff credentials";
    static final String SAME_PASSWORD = "New password must be different";

    private final StudentRepository studentRepository;
    private final TeacherRepository teacherRepository;
    private final ResearcherRepository researcherRepository;
    private final TeacherStudentLinkRepository linkRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final String dummyHash;

    public AuthService(
            StudentRepository studentRepository,
            TeacherRepository teacherRepository,
            ResearcherRepository researcherRepository,
            TeacherStudentLinkRepository linkRepository,
            PasswordEncoder passwordEncoder,
            TokenService tokenService) {
        this.studentRepository = studentRepository;
        this.teacherRepository = teacherRepository;
        this.researcherRepository = researcherRepository;
        this.linkRepository = linkRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.dummyHash = passwordEncoder.encode("staff-login-dummy-" + UUID.randomUUID());
    }

    /**
     * Un alumno sin vinculo activo (dado de baja) no entra, con el mismo mensaje que un PIN errado
     * para no revelar el motivo. Un login correcto marca el "ultimo acceso" de todos sus vinculos activos.
     */
    @Transactional
    public AuthResponse loginStudent(StudentLoginRequest request) {
        var student = studentRepository.findByUsername(request.username()).orElse(null);
        if (student == null || !passwordEncoder.matches(request.password(), student.getPasswordHash())) {
            throw new UnauthorizedException(INVALID_STUDENT);
        }
        List<TeacherStudentLink> activeLinks = linkRepository.findByStudentIdAndDeletedAtIsNull(student.getId());
        if (activeLinks.isEmpty()) {
            throw new UnauthorizedException(INVALID_STUDENT);
        }
        activeLinks.forEach(TeacherStudentLink::registerAccess);
        return tokenService.issue(student.getId(), UserRole.STUDENT);
    }

    @Transactional(readOnly = true)
    public AuthResponse loginTeacher(TeacherLoginRequest request) {
        var teacher = teacherRepository.findByEmailIgnoreCase(request.email().trim()).orElse(null);
        if (teacher == null || !passwordEncoder.matches(request.password(), teacher.getPasswordHash())) {
            throw new UnauthorizedException(INVALID_TEACHER);
        }
        return issueTeacher(teacher);
    }

    /**
     * Siempre ejecuta exactamente dos comparaciones BCrypt (real o ficticia por cada lado), de modo que el
     * tiempo de respuesta no revele si el correo pertenece a un investigador, a un docente o a nadie.
     */
    @Transactional(readOnly = true)
    public AuthResponse loginStaff(StaffLoginRequest request) {
        String email = request.email().trim();
        var researcher = researcherRepository.findByEmailIgnoreCase(email);
        var teacher = teacherRepository.findByEmailIgnoreCase(email);
        boolean researcherMatches = passwordEncoder.matches(request.password(),
                researcher.map(r -> r.getPasswordHash()).orElse(dummyHash));
        boolean teacherMatches = passwordEncoder.matches(request.password(),
                teacher.map(t -> t.getPasswordHash()).orElse(dummyHash));
        if (researcher.isPresent() && researcherMatches) {
                // El investigador no guarda nombre: el portal usa el derivado del correo.
            return tokenService.issue(researcher.get().getId(), UserRole.RESEARCHER, false, null);
        }
        if (teacher.isPresent() && teacherMatches) {
                return issueTeacher(teacher.get());
        }
        throw new UnauthorizedException(INVALID_STAFF);
    }

    @Transactional
    public void changeTeacherPassword(UUID teacherId, ChangePasswordRequest request) {
        Teacher teacher = teacherRepository.findById(teacherId)
                .orElseThrow(() -> new UnauthorizedException(INVALID_TEACHER));
        verifyPassword(request.currentPassword(), teacher.getPasswordHash(), INVALID_TEACHER);
        requireDifferent(request);
        teacher.changePassword(passwordEncoder.encode(request.newPassword()));
    }

    /** Cambio voluntario de contrasena del docente o del investigador (en el docente limpia mustChangePassword). */
    @Transactional
    public void changeStaffPassword(UUID userId, UserRole role, ChangePasswordRequest request) {
        if (role == UserRole.TEACHER) {
            Teacher teacher = teacherRepository.findById(userId)
                    .orElseThrow(() -> new UnauthorizedException(INVALID_STAFF));
            verifyPassword(request.currentPassword(), teacher.getPasswordHash(), INVALID_STAFF);
            requireDifferent(request);
            teacher.changePassword(passwordEncoder.encode(request.newPassword()));
            return;
        }
        if (role == UserRole.RESEARCHER) {
            Researcher researcher = researcherRepository.findById(userId)
                    .orElseThrow(() -> new UnauthorizedException(INVALID_STAFF));
            verifyPassword(request.currentPassword(), researcher.getPasswordHash(), INVALID_STAFF);
            requireDifferent(request);
            researcher.changePassword(passwordEncoder.encode(request.newPassword()));
            return;
        }
        throw new UnauthorizedException(INVALID_STAFF);
    }

    private AuthResponse issueTeacher(Teacher teacher) {
        String fullName = teacher.getFullName();
        String displayName = fullName == null || fullName.isBlank() ? null : fullName;
        return tokenService.issue(teacher.getId(), UserRole.TEACHER, teacher.isMustChangePassword(), displayName);
    }

    private static void requireDifferent(ChangePasswordRequest request) {
        if (request.currentPassword().equals(request.newPassword())) {
            throw new BusinessException(SAME_PASSWORD);
        }
    }

    private void verifyPassword(String rawPassword, String passwordHash, String message) {
        if (!passwordEncoder.matches(rawPassword, passwordHash)) {
            throw new UnauthorizedException(message);
        }
    }
}
