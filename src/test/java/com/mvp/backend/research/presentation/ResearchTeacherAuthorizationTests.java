package com.mvp.backend.research.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasLength;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.mvp.backend.research.domain.model.Researcher;
import com.mvp.backend.research.domain.repository.ResearcherRepository;
import com.mvp.backend.shared.security.PersonalDataCipher;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.student.domain.repository.StudentRepository;
import com.mvp.backend.teacher.domain.model.Classroom;
import com.mvp.backend.teacher.domain.model.Teacher;
import com.mvp.backend.teacher.domain.model.TeacherStudentLink;
import com.mvp.backend.teacher.domain.repository.ClassroomRepository;
import com.mvp.backend.teacher.domain.repository.TeacherRepository;
import com.mvp.backend.teacher.domain.repository.TeacherStudentLinkRepository;

@SpringBootTest
@AutoConfigureMockMvc
class ResearchTeacherAuthorizationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private ResearcherRepository researcherRepository;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private ClassroomRepository classroomRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private TeacherStudentLinkRepository linkRepository;
    @Autowired private PersonalDataCipher cipher;
    @Autowired private PasswordEncoder passwordEncoder;

    private UUID researcherId;
    private UUID teacherId;

    @BeforeEach
    void setUp() {
        researcherId = researcherRepository.save(new Researcher(UUID.randomUUID() + "@lab.edu", "hash")).getId();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Teacher teacher = teacherRepository.save(new Teacher(
                "doc-" + suffix,
                suffix + "@c.edu",
                null,
                "C",
                passwordEncoder.encode("Temporary23"),
                researcherId,
                true));
        teacherId = teacher.getId();
        Classroom classroom = classroomRepository.save(new Classroom(teacher, "3 B"));
        Student student = studentRepository.save(new Student("tigre-" + suffix, "C", "hash"));
        linkRepository.save(new TeacherStudentLink(
                teacher, student, classroom, cipher.encrypt("Nombre Real Secreto"), null));
    }

    @Test
    void researcherCreatesTeacherAndSeesTemporaryPasswordOnce() throws Exception {
        String username = "ana." + researcherId.toString().substring(0, 8);
        mockMvc.perform(post("/api/v1/research/teachers").with(researcher())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Ana Perez\",\"email\":\""
                                + username + "@c.edu\",\"institution\":\"C\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.temporaryPassword", hasLength(10)));

        mockMvc.perform(get("/api/v1/research/teachers").with(researcher()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("temporaryPassword"))));
    }

    @Test
    void classroomDirectoryNeverContainsRealNames() throws Exception {
        mockMvc.perform(get("/api/v1/research/classrooms").with(researcher()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Nombre Real Secreto"))))
                .andExpect(content().string(not(containsString("studentRealName"))))
                .andExpect(content().string(not(containsString("encryptedStudentRealName"))))
                .andExpect(jsonPath("$[?(@.name=='3 B')].students[0].username").exists());
    }

    @Test
    void teacherCannotCreateTeachers() throws Exception {
        mockMvc.perform(post("/api/v1/research/teachers")
                        .with(teacher())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"X\",\"email\":\"x@c.edu\",\"institution\":\"C\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void publicTeacherRegistrationIsGone() throws Exception {
        mockMvc.perform(post("/api/v1/auth/teachers/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"x\",\"email\":\"x@c.edu\",\"institution\":\"C\",\"password\":\"12345678\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void changePasswordRequiresTeacherToken() throws Exception {
        String request = "{\"currentPassword\":\"Temporary23\",\"newPassword\":\"Permanent45\"}";

        mockMvc.perform(post("/api/v1/auth/teachers/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().is4xxClientError());

        mockMvc.perform(post("/api/v1/auth/teachers/change-password")
                        .with(researcher())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isForbidden());
    }

    @Test
    void teacherChangesPasswordWithTeacherToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/teachers/change-password")
                        .with(teacher())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Temporary23\",\"newPassword\":\"Permanent45\"}"))
                .andExpect(status().isNoContent());

        Teacher teacher = teacherRepository.findById(teacherId).orElseThrow();
        assertThat(teacher.isMustChangePassword()).isFalse();
        assertThat(passwordEncoder.matches("Permanent45", teacher.getPasswordHash())).isTrue();
    }

    private RequestPostProcessor researcher() {
        return jwt().jwt(token -> token.subject(researcherId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_RESEARCHER"));
    }

    private RequestPostProcessor teacher() {
        return jwt().jwt(token -> token.subject(teacherId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_TEACHER"));
    }
}
