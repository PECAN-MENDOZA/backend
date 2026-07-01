package com.mvp.backend.report.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mvp.backend.kpi.application.dto.AcceptanceRateResponse;
import com.mvp.backend.kpi.application.dto.KpiSummaryResponse;
import com.mvp.backend.kpi.application.dto.TopWordItem;
import com.mvp.backend.kpi.application.service.KpiService;
import com.mvp.backend.report.application.dto.ReportPdfDocument;
import com.mvp.backend.report.domain.model.MonthlyReport;
import com.mvp.backend.report.domain.repository.MonthlyReportRepository;
import com.mvp.backend.report.infrastructure.pdf.SimplePdfGenerator;
import com.mvp.backend.shared.exception.NotFoundException;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.teacher.domain.model.Teacher;
import com.mvp.backend.teacher.domain.model.TeacherStudentLink;
import com.mvp.backend.teacher.domain.repository.TeacherRepository;
import com.mvp.backend.teacher.domain.repository.TeacherStudentLinkRepository;

@ExtendWith(MockitoExtension.class)
class ReportServiceTests {

    @Mock
    private KpiService kpiService;

    @Mock
    private MonthlyReportRepository monthlyReportRepository;

    @Mock
    private TeacherStudentLinkRepository linkRepository;

    @Mock
    private TeacherRepository teacherRepository;

    @Mock
    private SimplePdfGenerator pdfGenerator;

    private ReportService reportService;

    @BeforeEach
    void setUp() {
        reportService = new ReportService(
                kpiService,
                monthlyReportRepository,
                linkRepository,
                teacherRepository,
                pdfGenerator);
    }

    @Test
    void returnsUnavailableWhenThereIsNoSnapshotOrSubmissions() {
        UUID teacherId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        TeacherStudentLink link = link(studentId, "student_01", "Nicolas Herrera");

        when(kpiService.summary(teacherId, studentId, "2026-05"))
                .thenReturn(summary(studentId, "Nicolas Herrera", 0, 0, 0, 0));
        when(linkRepository.findByTeacherIdAndStudentIdAndDeletedAtIsNull(teacherId, studentId))
                .thenReturn(Optional.of(link));
        when(monthlyReportRepository.findByStudentIdAndMonth(studentId, LocalDate.of(2026, 5, 1)))
                .thenReturn(Optional.empty());

        var response = reportService.availability(teacherId, studentId, "2026-05");

        assertThat(response.available()).isFalse();
        assertThat(response.filename()).isNull();
    }

    @Test
    void returnsAvailabilityFromHistoricalSnapshot() {
        UUID teacherId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        TeacherStudentLink link = link(studentId, "student_02", "Nicolas Herrera");
        MonthlyReport snapshot = mock(MonthlyReport.class);

        when(kpiService.summary(teacherId, studentId, "2026-05"))
                .thenReturn(summary(studentId, "Nicolas Herrera", 0, 0, 0, 0));
        when(linkRepository.findByTeacherIdAndStudentIdAndDeletedAtIsNull(teacherId, studentId))
                .thenReturn(Optional.of(link));
        when(monthlyReportRepository.findByStudentIdAndMonth(studentId, LocalDate.of(2026, 5, 1)))
                .thenReturn(Optional.of(snapshot));
        when(snapshot.getGeneratedAt()).thenReturn(Instant.parse("2026-05-31T18:00:00Z"));

        var response = reportService.availability(teacherId, studentId, "2026-05");

        assertThat(response.available()).isTrue();
        assertThat(response.source()).isEqualTo("HISTORICAL_SNAPSHOT");
        assertThat(response.generatedAt()).isEqualTo(Instant.parse("2026-05-31T18:00:00Z"));
        assertThat(response.filename()).isEqualTo("reporte-nicolas-herrera-2026-05.pdf");
    }

    @Test
    void throwsNotFoundWhenPdfIsRequestedWithoutSnapshotOrSubmissions() {
        UUID teacherId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        TeacherStudentLink link = link(studentId, "student_03", "Nicolas Herrera");

        when(kpiService.summary(teacherId, studentId, "2026-05"))
                .thenReturn(summary(studentId, "Nicolas Herrera", 0, 0, 0, 0));
        when(linkRepository.findByTeacherIdAndStudentIdAndDeletedAtIsNull(teacherId, studentId))
                .thenReturn(Optional.of(link));
        when(monthlyReportRepository.findByStudentIdAndMonth(studentId, LocalDate.of(2026, 5, 1)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> reportService.downloadPdf(teacherId, studentId, "2026-05"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Report not found for requested month");
    }

    @Test
    void generatesPdfWhenReportDataExists() {
        UUID teacherId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        Teacher teacher = new Teacher("sofia.garcia", "sofia@school.edu", null, "School", "encoded");
        TeacherStudentLink link = link(studentId, "student_04", "Nicolas Herrera");
        byte[] pdfBytes = new byte[] {1, 2, 3};

        when(kpiService.summary(teacherId, studentId, "2026-05"))
                .thenReturn(summary(studentId, "Nicolas Herrera", 14, 11, 3, 0));
        when(linkRepository.findByTeacherIdAndStudentIdAndDeletedAtIsNull(teacherId, studentId))
                .thenReturn(Optional.of(link));
        when(monthlyReportRepository.findByStudentIdAndMonth(studentId, LocalDate.of(2026, 5, 1)))
                .thenReturn(Optional.empty());
        when(teacherRepository.findById(teacherId)).thenReturn(Optional.of(teacher));
        when(pdfGenerator.generate(org.mockito.ArgumentMatchers.any(ReportPdfDocument.class))).thenReturn(pdfBytes);

        var response = reportService.downloadPdf(teacherId, studentId, "2026-05");

        assertThat(response.filename()).isEqualTo("reporte-nicolas-herrera-2026-05.pdf");
        assertThat(response.content()).isEqualTo(pdfBytes);
    }

    private TeacherStudentLink link(UUID studentId, String alias, String name) {
        Teacher teacher = new Teacher("teacher_01", "teacher@school.edu", null, "School", "encoded");
        Student student = new Student(alias, "School", "encoded");
        return new TeacherStudentLink(teacher, student, "encrypted-name", "Seguimiento mensual.");
    }

    private KpiSummaryResponse summary(
            UUID studentId,
            String name,
            long totalSubmissions,
            long totalAccepted,
            long totalRejected,
            long unanswered) {
        return new KpiSummaryResponse(
                studentId,
                name,
                "2026-05",
                new AcceptanceRateResponse(studentId, "2026-05", totalSubmissions, totalAccepted, totalRejected, unanswered,
                        0, totalSubmissions == 0 ? 0 : 78.57),
                List.of(new TopWordItem("ermano", 2, 2)));
    }
}
