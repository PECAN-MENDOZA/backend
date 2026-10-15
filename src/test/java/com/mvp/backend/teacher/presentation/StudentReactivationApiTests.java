package com.mvp.backend.teacher.presentation;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.mvp.backend.shared.security.PersonalDataCipher;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.student.domain.repository.StudentRepository;
import com.mvp.backend.teacher.domain.model.Classroom;
import com.mvp.backend.teacher.domain.model.Teacher;
import com.mvp.backend.teacher.domain.model.TeacherStudentLink;
import com.mvp.backend.teacher.domain.repository.ClassroomRepository;
import com.mvp.backend.teacher.domain.repository.TeacherRepository;
import com.mvp.backend.teacher.domain.repository.TeacherStudentLinkRepository;

/** Contrato 2: listado de dados de baja y reactivacion. */
@SpringBootTest
@AutoConfigureMockMvc
class StudentReactivationApiTests {

    private static final String BASE = "/api/v1/teachers/students";

    @Autowired private MockMvc mockMvc;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private ClassroomRepository classroomRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private TeacherStudentLinkRepository linkRepository;
    @Autowired private PersonalDataCipher cipher;

    private UUID teacherId;
    private UUID otherTeacherId;
    private UUID classroomId;
    private UUID activeId;
    private UUID droppedId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Teacher teacher = teacherRepository.save(new Teacher("doc-" + suffix, suffix + "@c.edu", null, "C", "hash"));
        teacherId = teacher.getId();
        otherTeacherId = teacherRepository.save(
                new Teacher("otro-" + suffix, "otro-" + suffix + "@c.edu", null, "C", "hash")).getId();
        Classroom classroom = classroomRepository.save(new Classroom(teacher, "4.º A"));
        classroomId = classroom.getId();
        Student active = studentRepository.save(new Student("luna-" + suffix, "C", "hash"));
        Student dropped = studentRepository.save(new Student("tren-" + suffix, "C", "hash"));
        activeId = active.getId();
        droppedId = dropped.getId();
        linkRepository.save(new TeacherStudentLink(teacher, active, classroom, cipher.encrypt("Lucía"), null));
        TeacherStudentLink droppedLink = new TeacherStudentLink(teacher, dropped, classroom, cipher.encrypt("Tomás"), null);
        droppedLink.deactivate();
        linkRepository.save(droppedLink);
    }

    @Test
    void inactiveListShowsOnlyDroppedStudentsWithDeactivatedAt() throws Exception {
        mockMvc.perform(get(BASE + "/inactive").with(teacher(teacherId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].studentId").value(droppedId.toString()))
                .andExpect(jsonPath("$[0].classroomId").value(classroomId.toString()))
                .andExpect(jsonPath("$[0].studentRealName").value("Tomás"))
                .andExpect(jsonPath("$[0].deactivatedAt").isString());

        // En los listados normales el campo existe y va null.
        mockMvc.perform(get(BASE).with(teacher(teacherId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].studentId").value(activeId.toString()))
                .andExpect(jsonPath("$[0].deactivatedAt").isEmpty());

        mockMvc.perform(get(BASE + "/inactive").with(teacher(otherTeacherId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void reactivateRestoresTheLinkAndRejectsForeignOrActiveStudents() throws Exception {
        mockMvc.perform(post(BASE + "/{id}/reactivate", droppedId).with(teacher(otherTeacherId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/{id}/reactivate", activeId).with(teacher(teacherId)))
                .andExpect(status().isConflict());

        mockMvc.perform(post(BASE + "/{id}/reactivate", droppedId).with(teacher(teacherId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentId").value(droppedId.toString()))
                .andExpect(jsonPath("$.deactivatedAt").isEmpty());

        mockMvc.perform(get(BASE + "/inactive").with(teacher(teacherId)))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/v1/teachers/classrooms/{id}/students", classroomId).with(teacher(teacherId)))
                .andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(post(BASE + "/{id}/reactivate", droppedId).with(teacher(teacherId)))
                .andExpect(status().isConflict());
    }

    private static RequestPostProcessor teacher(UUID id) {
        return jwt().jwt(token -> token.subject(id.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_TEACHER"));
    }
}
