package com.mvp.backend.research.application.service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final TemporaryPasswordGenerator passwords;
    private final PasswordEncoder passwordEncoder;

    public ResearchTeacherService(
            TeacherRepository teacherRepository,
            ResearcherRepository researcherRepository,
            ClassroomRepository classroomRepository,
            TeacherStudentLinkRepository linkRepository,
            TemporaryPasswordGenerator passwords,
            PasswordEncoder passwordEncoder) {
        this.teacherRepository = teacherRepository;
        this.researcherRepository = researcherRepository;
        this.classroomRepository = classroomRepository;
        this.linkRepository = linkRepository;
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
                true));
        return new CreatedTeacherResponse(
                teacher.getId(),
                teacher.getUsername(),
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
        var students = linkRepository
                .findByClassroomIdAndDeletedAtIsNullOrderByCreatedAtAsc(classroom.getId())
                .stream()
                .map(link -> new ClassroomDirectoryResponse.StudentEntry(
                        link.getStudent().getId(),
                        link.getStudent().getUsername(),
                        link.getLastAccessAt()))
                .toList();
        return new ClassroomDirectoryResponse(
                classroom.getId(),
                classroom.getName(),
                classroom.getTeacher().getId(),
                classroom.getTeacher().getUsername(),
                classroom.isArchived(),
                students);
    }

    private String uniqueUsername(String email) {
        String base = email.substring(0, email.indexOf('@')).toLowerCase(Locale.ROOT);
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
