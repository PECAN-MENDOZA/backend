package com.mvp.backend.student.application.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mvp.backend.shared.exception.NotFoundException;
import com.mvp.backend.student.application.dto.StudentResponse;
import com.mvp.backend.student.domain.repository.StudentRepository;

@Service
public class StudentService {

    private final StudentRepository studentRepository;

    public StudentService(StudentRepository studentRepository) {
        this.studentRepository = studentRepository;
    }

    @Transactional(readOnly = true)
    public StudentResponse getById(UUID id) {
        return StudentResponse.from(studentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Student not found")));
    }
}
