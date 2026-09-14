package com.mvp.backend.auth.application.service;

import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mvp.backend.auth.application.dto.AuthResponse;
import com.mvp.backend.auth.application.dto.StaffLoginRequest;
import com.mvp.backend.auth.application.dto.StudentLoginRequest;
import com.mvp.backend.auth.application.dto.TeacherLoginRequest;
import com.mvp.backend.auth.application.dto.TeacherRegistrationRequest;
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

    @Transactional
    public AuthResponse registerTeacher(TeacherRegistrationRequest request) {
        if (teacherRepository.existsByUsername(request.username())) {
            throw new BusinessException("Teacher username is already in use");
        }
        if (teacherRepository.existsByEmail(request.email())) {
            throw new BusinessException("Teacher email is already in use");
        }
        var teacher = new Teacher(
                request.username(),
                request.email(),
                request.phone(),
                request.institution(),
                passwordEncoder.encode(request.password()));
        teacherRepository.save(teacher);
        return tokenService.issue(teacher.getId(), UserRole.TEACHER);
    }

    @Transactional(readOnly = true)
    public AuthResponse loginTeacher(TeacherLoginRequest request) {
        var teacher = teacherRepository.findByEmail(request.email())
                .orElseThrow(() -> new UnauthorizedException("Invalid teacher credentials"));
        verifyPassword(request.password(), teacher.getPasswordHash(), "Invalid teacher credentials");
        return tokenService.issue(teacher.getId(), UserRole.TEACHER);
    }

    @Transactional(readOnly = true)
    public AuthResponse loginStaff(StaffLoginRequest request) {
        var researcher = researcherRepository.findByEmail(request.email());
        if (researcher.isPresent()
                && passwordEncoder.matches(request.password(), researcher.get().getPasswordHash())) {
            return tokenService.issue(researcher.get().getId(), UserRole.RESEARCHER);
        }
        var teacher = teacherRepository.findByEmail(request.email());
        if (teacher.isPresent()
                && passwordEncoder.matches(request.password(), teacher.get().getPasswordHash())) {
            return tokenService.issue(teacher.get().getId(), UserRole.TEACHER);
        }
        // Comparación ficticia: el tiempo de respuesta no debe revelar si el correo existe.
        passwordEncoder.matches(request.password(), dummyHash);
        throw new UnauthorizedException("Invalid staff credentials");
    }

    private void verifyPassword(String rawPassword, String passwordHash, String message) {
        if (!passwordEncoder.matches(rawPassword, passwordHash)) {
            throw new UnauthorizedException(message);
        }
    }
}
