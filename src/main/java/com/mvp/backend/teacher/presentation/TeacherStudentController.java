package com.mvp.backend.teacher.presentation;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mvp.backend.teacher.application.dto.CreateStudentLinkRequest;
import com.mvp.backend.teacher.application.dto.StudentLinkResponse;
import com.mvp.backend.teacher.application.service.TeacherStudentService;

@RestController
@RequestMapping("/api/v1/teachers/students")
@PreAuthorize("hasRole('TEACHER')")
public class TeacherStudentController {

    private final TeacherStudentService teacherStudentService;

    public TeacherStudentController(TeacherStudentService teacherStudentService) {
        this.teacherStudentService = teacherStudentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StudentLinkResponse linkStudent(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateStudentLinkRequest request) {
        return teacherStudentService.linkStudent(UUID.fromString(jwt.getSubject()), request);
    }

    @GetMapping
    public List<StudentLinkResponse> listStudents(@AuthenticationPrincipal Jwt jwt) {
        return teacherStudentService.listStudents(UUID.fromString(jwt.getSubject()));
    }
}
