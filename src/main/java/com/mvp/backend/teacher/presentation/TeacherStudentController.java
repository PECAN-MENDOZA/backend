package com.mvp.backend.teacher.presentation;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mvp.backend.teacher.application.dto.CreateLinkedStudentRequest;
import com.mvp.backend.teacher.application.dto.CreatedStudentAccountResponse;
import com.mvp.backend.teacher.application.dto.ResetStudentPinResponse;
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

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedStudentAccountResponse createLinkedStudent(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateLinkedStudentRequest request) {
        return teacherStudentService.createLinkedStudent(UUID.fromString(jwt.getSubject()), request);
    }

    @GetMapping
    public List<StudentLinkResponse> listStudents(@AuthenticationPrincipal Jwt jwt) {
        return teacherStudentService.listStudents(UUID.fromString(jwt.getSubject()));
    }

    @PostMapping("/{studentId}/reset-pin")
    public ResetStudentPinResponse resetPin(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studentId) {
        return teacherStudentService.resetPin(UUID.fromString(jwt.getSubject()), studentId);
    }
}
