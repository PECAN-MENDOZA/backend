package com.mvp.backend.teacher.presentation;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.mvp.backend.teacher.domain.model.Classroom;
import com.mvp.backend.teacher.domain.model.Teacher;
import com.mvp.backend.teacher.domain.repository.ClassroomRepository;
import com.mvp.backend.teacher.domain.repository.TeacherRepository;

@SpringBootTest
@AutoConfigureMockMvc
class ClassroomAuthorizationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private ClassroomRepository classroomRepository;

    private UUID teacherId;
    private UUID otherTeacherId;
    private UUID classroomId;

    @BeforeEach
    void setUp() {
        Teacher teacher = teacherRepository.save(newTeacher());
        otherTeacherId = teacherRepository.save(newTeacher()).getId();
        teacherId = teacher.getId();
        classroomId = classroomRepository.save(new Classroom(teacher, "3.º B")).getId();
    }

    @Test
    void teacherCreatesStudentsInsideOwnClassroom() throws Exception {
        mockMvc.perform(post("/api/v1/teachers/classrooms/{id}/students", classroomId)
                .with(teacher(teacherId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"count\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].pin").isString())
                .andExpect(jsonPath("$[0].classroomId").value(classroomId.toString()));
    }

    @Test
    void archivedClassroomNameCanBeReusedButActiveDuplicateIsConflict() throws Exception {
        mockMvc.perform(post("/api/v1/teachers/classrooms").with(teacher(teacherId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"3.º B\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Classroom name is already in use"));

        mockMvc.perform(patch("/api/v1/teachers/classrooms/{id}", classroomId).with(teacher(teacherId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"archived\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").isNotEmpty());

        mockMvc.perform(post("/api/v1/teachers/classrooms").with(teacher(teacherId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"3.º B\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("3.º B"));

        // Desarchivar el antiguo chocaria con el nuevo activo.
        mockMvc.perform(patch("/api/v1/teachers/classrooms/{id}", classroomId).with(teacher(teacherId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"archived\":false}"))
                .andExpect(status().isConflict());
    }

    @Test
    void anotherTeacherCannotSeeTheClassroom() throws Exception {
        mockMvc.perform(get("/api/v1/teachers/classrooms/{id}/students", classroomId)
                .with(teacher(otherTeacherId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void researcherCannotUseTeacherClassroomEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/teachers/classrooms")
                .with(jwt().jwt(t -> t.subject(UUID.randomUUID().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_RESEARCHER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void legacyAccountsEndpointIsGone() throws Exception {
        mockMvc.perform(post("/api/v1/teachers/students/accounts").with(teacher(teacherId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"studentRealName\":\"Ana\"}"))
                .andExpect(status().isNotFound());
    }

    private static RequestPostProcessor teacher(UUID id) {
        return jwt().jwt(t -> t.subject(id.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_TEACHER"));
    }

    private static Teacher newTeacher() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return new Teacher("docente-" + suffix, suffix + "@colegio.edu.pe", null, "Colegio", "hash");
    }
}
