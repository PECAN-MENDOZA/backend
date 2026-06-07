package com.mvp.backend.student.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.student.domain.repository.StudentRepository;

@ExtendWith(MockitoExtension.class)
class StudentServiceTests {

    @Mock
    private StudentRepository studentRepository;

    private StudentService studentService;

    @BeforeEach
    void setUp() {
        studentService = new StudentService(studentRepository);
    }

    @Test
    void returnsPseudonymousProfileById() {
        Student student = new Student("tigre-07", "School 01", "encoded-pin");
        when(studentRepository.findById(student.getId())).thenReturn(Optional.of(student));

        var response = studentService.getById(student.getId());

        assertThat(response.username()).isEqualTo("tigre-07");
        assertThat(response.institution()).isEqualTo("School 01");
    }
}
