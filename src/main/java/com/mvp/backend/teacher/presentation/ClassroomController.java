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

import com.mvp.backend.teacher.application.dto.ClassroomResponse;
import com.mvp.backend.teacher.application.dto.CreateClassroomRequest;
import com.mvp.backend.teacher.application.dto.CreateClassroomStudentsRequest;
import com.mvp.backend.teacher.application.dto.CreatedStudentAccountResponse;
import com.mvp.backend.teacher.application.dto.StudentLinkResponse;
import com.mvp.backend.teacher.application.dto.UpdateClassroomRequest;
import com.mvp.backend.teacher.application.service.ClassroomService;

@RestController
@RequestMapping("/api/v1/teachers/classrooms")
@PreAuthorize("hasRole('TEACHER')")
public class ClassroomController {

    private final ClassroomService classroomService;

    public ClassroomController(ClassroomService classroomService) {
        this.classroomService = classroomService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClassroomResponse create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateClassroomRequest request) {
        return classroomService.createClassroom(teacherId(jwt), request);
    }

    @GetMapping
    public List<ClassroomResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return classroomService.listClassrooms(teacherId(jwt));
    }

    @PatchMapping("/{classroomId}")
    public ClassroomResponse update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID classroomId,
            @Valid @RequestBody UpdateClassroomRequest request) {
        return classroomService.updateClassroom(teacherId(jwt), classroomId, request);
    }

    @GetMapping("/{classroomId}/students")
    public List<StudentLinkResponse> students(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID classroomId) {
        return classroomService.listStudents(teacherId(jwt), classroomId);
    }

    @PostMapping("/{classroomId}/students")
    @ResponseStatus(HttpStatus.CREATED)
    public List<CreatedStudentAccountResponse> createStudents(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID classroomId,
            @Valid @RequestBody CreateClassroomStudentsRequest request) {
        return classroomService.createStudents(teacherId(jwt), classroomId, request);
    }

    private static UUID teacherId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
