package com.mvp.backend.teacher.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mvp.backend.student.domain.repository.StudentRepository;

@ExtendWith(MockitoExtension.class)
class StudentCredentialGeneratorTests {

    @Mock
    private StudentRepository studentRepository;

    @Test
    void aliasIsWordDashTwoDigitsAndPinHasFourDigits() {
        when(studentRepository.existsByUsername(anyString())).thenReturn(false);
        var generator = new StudentCredentialGenerator(studentRepository);

        assertThat(generator.newAlias()).matches("[a-z]+-\\d{2}");
        assertThat(generator.newPin()).matches("\\d{4}");
    }

    @Test
    void aliasWidensTheNumberWhenTwoDigitSpaceIsExhausted() {
        when(studentRepository.existsByUsername(anyString())).thenAnswer(inv ->
                ((String) inv.getArgument(0)).matches("[a-z]+-\\d{2}"));
        var generator = new StudentCredentialGenerator(studentRepository);

        assertThat(generator.newAlias()).matches("[a-z]+-\\d{4}");
    }
}
