package com.mvp.backend.student.application.dto;

import java.time.Instant;
import java.util.UUID;

import com.mvp.backend.student.domain.model.Student;

public record StudentResponse(UUID id, String username, String institution, Instant createdAt) {

    public static StudentResponse from(Student student) {
        return new StudentResponse(
                student.getId(),
                student.getUsername(),
                student.getInstitution(),
                student.getCreatedAt());
    }
}
