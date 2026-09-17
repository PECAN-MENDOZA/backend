package com.mvp.backend.auth.application.service;

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
import com.mvp.backend.research.domain.repository.ResearcherRepository;
import com.mvp.backend.shared.exception.BusinessException;
import com.mvp.backend.shared.exception.UnauthorizedException;
import com.mvp.backend.student.domain.repository.StudentRepository;
import com.mvp.backend.teacher.domain.model.Teacher;
import com.mvp.backend.teacher.domain.repository.TeacherRepository;

@Service
public class AuthService {

    private final StudentRepository studentRepository;
    private final TeacherRepository teacherRepository;
    private final ResearcherRepository researcherRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final String dummyHash;

    public AuthService(
            StudentRepository studentRepository,
            TeacherRepository teacherRepository,
            ResearcherRepository researcherRepository,
            PasswordEncoder passwordEncoder,
            TokenService tokenService) {
        this.studentRepository = studentRepository;
        this.teacherRepository = teacherRepository;
        this.researcherRepository = researcherRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.dummyHash = passwordEncoder.encode("staff-login-dummy-" + UUID.randomUUID());
    }

    @Transactional(readOnly = true)
    public AuthResponse loginStudent(StudentLoginRequest request) {
        var student = studentRepository.findByUsername(request.username())
                .orElseThrow(() -> new UnauthorizedException("Invalid student credentials"));
        verifyPassword(request.password(), student.getPasswordHash(), "Invalid student credentials");
        return tokenService.issue(student.getId(), UserRole.STUDENT);
    }

    @Transactional(readOnly = true)
    public AuthResponse loginTeacher(TeacherLoginRequest request) {
        var teacher = teacherRepository.findByEmail(request.email())
                .orElseThrow(() -> new UnauthorizedException("Invalid teacher credentials"));
        verifyPassword(request.password(), teacher.getPasswordHash(), "Invalid teacher credentials");
        return tokenService.issue(teacher.getId(), UserRole.TEACHER, teacher.isMustChangePassword());
    }

    /**
     * Siempre ejecuta exactamente dos comparaciones BCrypt (real o ficticia por cada lado), de modo que el
     * tiempo de respuesta no revele si el correo pertenece a un investigador, a un docente o a nadie.
     */
    @Transactional(readOnly = true)
    public AuthResponse loginStaff(StaffLoginRequest request) {
        var researcher = researcherRepository.findByEmail(request.email());
        var teacher = teacherRepository.findByEmail(request.email());
        boolean researcherMatches = passwordEncoder.matches(request.password(),
                researcher.map(r -> r.getPasswordHash()).orElse(dummyHash));
        boolean teacherMatches = passwordEncoder.matches(request.password(),
                teacher.map(t -> t.getPasswordHash()).orElse(dummyHash));
        if (researcher.isPresent() && researcherMatches) {
            return tokenService.issue(researcher.get().getId(), UserRole.RESEARCHER);
        }
        if (teacher.isPresent() && teacherMatches) {
            return tokenService.issue(
                    teacher.get().getId(), UserRole.TEACHER, teacher.get().isMustChangePassword());
        }
        throw new UnauthorizedException("Invalid staff credentials");
    }

    @Transactional
    public void changeTeacherPassword(UUID teacherId, ChangePasswordRequest request) {
        Teacher teacher = teacherRepository.findById(teacherId)
                .orElseThrow(() -> new UnauthorizedException("Invalid teacher credentials"));
        verifyPassword(request.currentPassword(), teacher.getPasswordHash(), "Invalid teacher credentials");
        if (request.currentPassword().equals(request.newPassword())) {
            throw new BusinessException("New password must be different");
        }
        teacher.changePassword(passwordEncoder.encode(request.newPassword()));
    }

    private void verifyPassword(String rawPassword, String passwordHash, String message) {
        if (!passwordEncoder.matches(rawPassword, passwordHash)) {
            throw new UnauthorizedException(message);
        }
    }
}
