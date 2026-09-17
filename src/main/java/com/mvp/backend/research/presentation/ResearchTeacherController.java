package com.mvp.backend.research.presentation;

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

import com.mvp.backend.research.application.dto.ClassroomDirectoryResponse;
import com.mvp.backend.research.application.dto.CreateTeacherRequest;
import com.mvp.backend.research.application.dto.CreatedTeacherResponse;
import com.mvp.backend.research.application.dto.TeacherSummaryResponse;
import com.mvp.backend.research.application.dto.TemporaryPasswordResponse;
import com.mvp.backend.research.application.service.ResearchTeacherService;

@RestController
@RequestMapping("/api/v1/research")
@PreAuthorize("hasRole('RESEARCHER')")
public class ResearchTeacherController {

    private final ResearchTeacherService service;

    public ResearchTeacherController(ResearchTeacherService service) {
        this.service = service;
    }

    @PostMapping("/teachers")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedTeacherResponse create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateTeacherRequest request) {
        return service.createTeacher(UUID.fromString(jwt.getSubject()), request);
    }

    @GetMapping("/teachers")
    public List<TeacherSummaryResponse> list() {
        return service.listTeachers();
    }

    @PostMapping("/teachers/{teacherId}/reset-password")
    public TemporaryPasswordResponse resetPassword(@PathVariable UUID teacherId) {
        return service.resetPassword(teacherId);
    }

    @GetMapping("/classrooms")
    public List<ClassroomDirectoryResponse> classrooms() {
        return service.classroomDirectory();
    }
}
