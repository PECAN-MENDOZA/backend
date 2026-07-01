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
                .thenReturn(new AcceptanceSummaryProjectionStub(20L, 12L, 5L, 3L, 4L));

        var response = kpiService.acceptanceRate(teacherId, studentId, "2026-05");

        assertThat(response.totalSubmissions()).isEqualTo(20);
        assertThat(response.totalAccepted()).isEqualTo(12);
        assertThat(response.totalRejected()).isEqualTo(5);
        assertThat(response.unanswered()).isEqualTo(3);
        assertThat(response.totalEdited()).isEqualTo(4);
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
                .thenReturn(new AcceptanceSummaryProjectionStub(0L, null, null, null, null));

        var response = kpiService.acceptanceRate(teacherId, studentId, "2026-05");

        assertThat(response.totalSubmissions()).isZero();
        assertThat(response.totalAccepted()).isZero();
        assertThat(response.totalRejected()).isZero();
        assertThat(response.unanswered()).isZero();
        assertThat(response.totalEdited()).isZero();
        assertThat(response.acceptanceRatePercentage()).isZero();
    }

    @Test
    void aggregatesErrorTypesByFrequency() {
        UUID teacherId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        Teacher teacher = new Teacher("teacher_03", "teacher3@school.edu", null, "School", "encoded");
        Student student = new Student("student_03", "School", "encoded");
        TeacherStudentLink link = new TeacherStudentLink(teacher, student, "encrypted-name", null);

        when(linkRepository.findByTeacherIdAndStudentIdAndDeletedAtIsNull(teacherId, studentId))
                .thenReturn(Optional.of(link));
        when(wordCorrectionRepository.wordPairsForMonth(studentId, Instant.parse("2026-05-01T00:00:00Z"),
                Instant.parse("2026-06-01T00:00:00Z")))
                .thenReturn(java.util.List.of(
                        new Object[] {"jugo", "jugó"},     // TILDE
                        new Object[] {"cancion", "canción"}, // TILDE
                        new Object[] {"bamos", "vamos"}));   // CONFUSION_B_V

        var response = kpiService.errorTypes(teacherId, studentId, "2026-05");

        assertThat(response.totalErrors()).isEqualTo(3);
        assertThat(response.errorTypes()).hasSize(2);
        // Ordenado por frecuencia descendente: TILDE (2) primero.
        assertThat(response.errorTypes().get(0).errorType()).isEqualTo("TILDE");
        assertThat(response.errorTypes().get(0).count()).isEqualTo(2);
        assertThat(response.errorTypes().get(0).percentage()).isEqualTo(66.67);
        assertThat(response.errorTypes().get(1).errorType()).isEqualTo("CONFUSION_B_V");
        assertThat(response.errorTypes().get(1).count()).isEqualTo(1);
    }

    @Test
    void buildsAcceptanceTrendFillingMissingMonths() {
        UUID teacherId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        Teacher teacher = new Teacher("teacher_04", "teacher4@school.edu", null, "School", "encoded");
        Student student = new Student("student_04", "School", "encoded");
        TeacherStudentLink link = new TeacherStudentLink(teacher, student, "encrypted-name", null);

        when(linkRepository.findByTeacherIdAndStudentIdAndDeletedAtIsNull(teacherId, studentId))
                .thenReturn(Optional.of(link));
        when(sessionRepository.monthlyAcceptance(studentId, Instant.parse("2026-03-01T00:00:00Z"),
                Instant.parse("2026-06-01T00:00:00Z")))
                .thenReturn(java.util.List.of(
                        new Object[] {"2026-03", 10L, 6L},
                        new Object[] {"2026-05", 4L, 4L}));

        var response = kpiService.acceptanceTrend(teacherId, studentId, "2026-03", "2026-05");

        assertThat(response.series()).hasSize(3);
        assertThat(response.series().get(0).month()).isEqualTo("2026-03");
        assertThat(response.series().get(0).percentage()).isEqualTo(60.0);
        // Abril no tiene datos: se rellena con ceros.
        assertThat(response.series().get(1).month()).isEqualTo("2026-04");
        assertThat(response.series().get(1).total()).isZero();
        assertThat(response.series().get(1).percentage()).isZero();
        assertThat(response.series().get(2).month()).isEqualTo("2026-05");
        assertThat(response.series().get(2).percentage()).isEqualTo(100.0);
    }

    private record AcceptanceSummaryProjectionStub(
            Long totalSessions,
            Long acceptedSessions,
            Long rejectedSessions,
            Long unansweredSessions,
            Long editedSessions) implements CorrectionSessionRepository.AcceptanceSummaryProjection {

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

        @Override
        public Long getEditedSessions() {
            return editedSessions;
        }
    }
}
