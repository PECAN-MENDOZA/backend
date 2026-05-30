package com.mvp.backend.student.presentation;

import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import jakarta.validation.Valid;

import com.mvp.backend.shared.dto.PagedResponse;
import com.mvp.backend.student.application.dto.StudentResponse;
import com.mvp.backend.student.application.dto.StudentSearchCriteria;
import com.mvp.backend.student.application.dto.CreateStudentRequest;
import com.mvp.backend.student.application.service.StudentService;

@RestController
@RequestMapping("/api/v1/students")
public class StudentController {

    private final StudentService studentService;

    public StudentController(StudentService studentService) {
        this.studentService = studentService;
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('STUDENT')")
    public StudentResponse me(@AuthenticationPrincipal Jwt jwt) {
        return studentService.getById(UUID.fromString(jwt.getSubject()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('TEACHER')")
    public StudentResponse create(@Valid @RequestBody CreateStudentRequest request) {
        return studentService.create(request);
    }

    @GetMapping
    @PreAuthorize("hasRole('TEACHER')")
    public PagedResponse<StudentResponse> search(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String institution,
            Pageable pageable) {
        return studentService.search(new StudentSearchCriteria(username, institution), pageable);
    }
}
