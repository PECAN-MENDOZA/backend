package com.mvp.backend.teacher.presentation;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mvp.backend.teacher.application.dto.MoveStudentRequest;
import com.mvp.backend.teacher.application.dto.ResetStudentPinResponse;
import com.mvp.backend.teacher.application.dto.StudentLinkResponse;
import com.mvp.backend.teacher.application.dto.UpdateStudentLinkRequest;
import com.mvp.backend.teacher.application.service.TeacherStudentService;

@RestController
@RequestMapping("/api/v1/teachers/students")
@PreAuthorize("hasRole('TEACHER')")
public class TeacherStudentController {

    private final TeacherStudentService teacherStudentService;

    public TeacherStudentController(TeacherStudentService teacherStudentService) {
        this.teacherStudentService = teacherStudentService;
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

    @PatchMapping("/{studentId:[0-9a-fA-F-]{36}}")
    public StudentLinkResponse update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studentId,
            @Valid @RequestBody UpdateStudentLinkRequest request) {
        return teacherStudentService.updateStudent(UUID.fromString(jwt.getSubject()), studentId, request);
    }

    @PatchMapping("/{studentId}/classroom")
    public StudentLinkResponse move(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studentId,
            @Valid @RequestBody MoveStudentRequest request) {
        return teacherStudentService.moveStudent(UUID.fromString(jwt.getSubject()), studentId, request);
    }

    @PostMapping("/{studentId}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID studentId) {
        teacherStudentService.deactivateStudent(UUID.fromString(jwt.getSubject()), studentId);
    }
}
