package com.mvp.backend.student.application.service;

import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mvp.backend.shared.dto.PagedResponse;
import com.mvp.backend.shared.exception.NotFoundException;
import com.mvp.backend.shared.exception.BusinessException;
import com.mvp.backend.student.application.dto.CreateStudentRequest;
import com.mvp.backend.student.application.dto.StudentResponse;
import com.mvp.backend.student.application.dto.StudentSearchCriteria;
import com.mvp.backend.student.domain.repository.StudentRepository;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.student.infrastructure.specification.StudentSpecifications;

@Service
public class StudentService {

    private final StudentRepository studentRepository;
    private final PasswordEncoder passwordEncoder;

    public StudentService(StudentRepository studentRepository, PasswordEncoder passwordEncoder) {
        this.studentRepository = studentRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public StudentResponse create(CreateStudentRequest request) {
        if (studentRepository.existsByUsername(request.username())) {
            throw new BusinessException("Student username is already in use");
        }
        Student student = new Student(
                request.username(),
                request.institution(),
                passwordEncoder.encode(request.temporaryPassword()));
        return StudentResponse.from(studentRepository.save(student));
    }

    @Transactional(readOnly = true)
    public StudentResponse getById(UUID id) {
        return StudentResponse.from(studentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Student not found")));
    }

    @Transactional(readOnly = true)
    public PagedResponse<StudentResponse> search(StudentSearchCriteria criteria, Pageable pageable) {
        return PagedResponse.from(studentRepository.findAll(StudentSpecifications.withCriteria(criteria), pageable)
                .map(StudentResponse::from));
    }
}
