package com.mvp.backend.kpi.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mvp.backend.correction.domain.repository.CorrectionSessionRepository;
import com.mvp.backend.correction.domain.repository.WordCorrectionRepository;
import com.mvp.backend.shared.security.PersonalDataCipher;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.teacher.domain.model.Teacher;
import com.mvp.backend.teacher.domain.model.TeacherStudentLink;
import com.mvp.backend.teacher.domain.repository.TeacherStudentLinkRepository;

@ExtendWith(MockitoExtension.class)
class KpiServiceTests {

    @Mock
    private CorrectionSessionRepository sessionRepository;

    @Mock
    private WordCorrectionRepository wordCorrectionRepository;

    @Mock
    private TeacherStudentLinkRepository linkRepository;

    @Mock
    private PersonalDataCipher personalDataCipher;

    private KpiService kpiService;

    @BeforeEach
    void setUp() {
        kpiService = new KpiService(sessionRepository, wordCorrectionRepository, linkRepository, personalDataCipher);
    }

    @Test
    void returnsAcceptanceRateUsingProjectionSummary() {
        UUID teacherId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        Teacher teacher = new Teacher("teacher_01", "teacher@school.edu", null, "School", "encoded");
        Student student = new Student("student_01", "School", "encoded");
        TeacherStudentLink link = new TeacherStudentLink(teacher, student, "encrypted-name", null);

        when(linkRepository.findByTeacherIdAndStudentIdAndDeletedAtIsNull(teacherId, studentId))
                .thenReturn(Optional.of(link));
        when(sessionRepository.acceptanceSummary(studentId, Instant.parse("2026-05-01T00:00:00Z"),
                Instant.parse("2026-06-01T00:00:00Z")))
                .thenReturn(new AcceptanceSummaryProjectionStub(20L, 12L, 5L, 3L));

        var response = kpiService.acceptanceRate(teacherId, studentId, "2026-05");

        assertThat(response.totalSubmissions()).isEqualTo(20);
        assertThat(response.totalAccepted()).isEqualTo(12);
        assertThat(response.totalRejected()).isEqualTo(5);
        assertThat(response.unanswered()).isEqualTo(3);
        assertThat(response.acceptanceRatePercentage()).isEqualTo(60.0);
    }

    @Test
    void treatsNullSummaryValuesAsZero() {
        UUID teacherId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        Teacher teacher = new Teacher("teacher_02", "teacher2@school.edu", null, "School", "encoded");
        Student student = new Student("student_02", "School", "encoded");
        TeacherStudentLink link = new TeacherStudentLink(teacher, student, "encrypted-name", null);

        when(linkRepository.findByTeacherIdAndStudentIdAndDeletedAtIsNull(teacherId, studentId))
                .thenReturn(Optional.of(link));
        when(sessionRepository.acceptanceSummary(studentId, Instant.parse("2026-05-01T00:00:00Z"),
                Instant.parse("2026-06-01T00:00:00Z")))
                .thenReturn(new AcceptanceSummaryProjectionStub(0L, null, null, null));

        var response = kpiService.acceptanceRate(teacherId, studentId, "2026-05");

        assertThat(response.totalSubmissions()).isZero();
        assertThat(response.totalAccepted()).isZero();
        assertThat(response.totalRejected()).isZero();
        assertThat(response.unanswered()).isZero();
        assertThat(response.acceptanceRatePercentage()).isZero();
    }

    private record AcceptanceSummaryProjectionStub(
            Long totalSessions,
            Long acceptedSessions,
            Long rejectedSessions,
            Long unansweredSessions) implements CorrectionSessionRepository.AcceptanceSummaryProjection {

        @Override
        public Long getTotalSessions() {
            return totalSessions;
        }

        @Override
        public Long getAcceptedSessions() {
            return acceptedSessions;
        }

        @Override
        public Long getRejectedSessions() {
            return rejectedSessions;
        }

        @Override
        public Long getUnansweredSessions() {
            return unansweredSessions;
        }
    }
}
