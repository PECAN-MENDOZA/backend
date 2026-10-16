package com.mvp.backend.report.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mvp.backend.insights.application.dto.ErrorTypeBreakdown.WordPair;
import com.mvp.backend.insights.application.dto.StudentErrorsResponse;
import com.mvp.backend.insights.application.dto.StudentHelpResponse;
import com.mvp.backend.insights.application.dto.StudentWritingItem;
import com.mvp.backend.insights.application.service.StudentInsightsService;
import com.mvp.backend.insights.application.service.TeacherAccess;
import com.mvp.backend.report.application.dto.ReportPdfDocument;
import com.mvp.backend.report.application.dto.ReportPdfDownload;
import com.mvp.backend.report.infrastructure.pdf.SimplePdfGenerator;
import com.mvp.backend.shared.exception.BusinessException;
import com.mvp.backend.shared.exception.ForbiddenException;
import com.mvp.backend.student.domain.model.Student;
import com.mvp.backend.teacher.domain.model.Classroom;
import com.mvp.backend.teacher.domain.model.Teacher;
import com.mvp.backend.teacher.domain.model.TeacherStudentLink;

@ExtendWith(MockitoExtension.class)
class ReportServiceTests {

    private static final String FROM = "2026-09-10";
    private static final String TO = "2026-09-17";

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-18T12:00:00Z"), ZoneOffset.UTC);

    @Mock private TeacherAccess access;
    @Mock private StudentInsightsService insights;
    @Mock private SimplePdfGenerator pdfGenerator;

    private final UUID teacherId = UUID.randomUUID();
    private final Teacher teacher = new Teacher("sofia.garcia", "sofia@colegio.edu.pe", null, "Colegio", "hash");
    private final Student ana = new Student("ana01", "Colegio", "hash");
    private final TeacherStudentLink link =
            new TeacherStudentLink(teacher, ana, new Classroom(teacher, "3.º B"), "enc-ana", "Le cuesta la h.");

    @Test
    void downloadPdfProducesARealPdfNamedByAliasAndPeriod() {
        stubInsights();
        ReportService service = new ReportService(access, insights, new SimplePdfGenerator(), clock);

        ReportPdfDownload download = service.downloadPdf(teacherId, ana.getId(), FROM, TO);

        assertThat(download.filename()).isEqualTo("reporte-ana01-2026-09-10_2026-09-17.pdf");
        assertThat(new String(download.content(), 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(download.content().length).isGreaterThan(1500);
    }

    @Test
    void downloadPdfDescribesThePeriodWithoutTrendsAndCapsExamplesAndWritings() {
        stubInsights();
        when(pdfGenerator.generate(any(ReportPdfDocument.class))).thenReturn(new byte[] {1});
        ReportService service = new ReportService(access, insights, pdfGenerator, clock);

        service.downloadPdf(teacherId, ana.getId(), FROM, TO);

        ArgumentCaptor<ReportPdfDocument> captor = ArgumentCaptor.forClass(ReportPdfDocument.class);
        verify(pdfGenerator).generate(captor.capture());
        ReportPdfDocument document = captor.getValue();
        assertThat(document.title()).isEqualTo("Reporte del periodo");
        assertThat(document.studentName()).isEqualTo("Ana Pérez");
        assertThat(document.studentAlias()).isEqualTo("ana01");
        assertThat(document.classroomName()).isEqualTo("3.º B");
        assertThat(document.classroomArchived()).isFalse();
        assertThat(document.teacherName()).isEqualTo("sofia.garcia");
        // Fechas para leer en dd/mm/aaaa; from/to siguen en ISO.
        assertThat(document.periodLabel()).isEqualTo("10/09/2026 – 17/09/2026");
        assertThat(document.from()).isEqualTo(FROM);
        assertThat(document.to()).isEqualTo(TO);
        assertThat(document.generatedAt()).isEqualTo(clock.instant());
        assertThat(document.help()).isEqualTo(new ReportPdfDocument.HelpSummary(6, 2, 1, 1, 0, 1, 1));
        assertThat(document.errorTypes()).hasSize(2);
        assertThat(document.errorTypes().get(0).label()).isEqualTo("Tildes y acentuación");
        assertThat(document.errorTypes().get(0).count()).isEqualTo(6);
        // Como maximo tres ejemplos por tipo en el PDF.
        assertThat(document.errorTypes().get(0).examples()).extracting(ReportPdfDocument.WordEntry::original)
                .containsExactly("camion", "papa", "arbol");
        assertThat(document.practiceWords()).containsExactly(
                new ReportPdfDocument.WordEntry("camion", "camión", 3),
                new ReportPdfDocument.WordEntry("papa", "papá", 2));
        assertThat(document.writings()).hasSize(20);
        assertThat(document.writings().get(0)).isEqualTo(new ReportPdfDocument.WritingEntry(
                Instant.parse("2026-09-17T15:00:00Z"), "escritura 0", "final 0", "Aceptó", false));
        assertThat(document.teacherNotes()).isEqualTo("Le cuesta la h.");
        // Las ultimas 20 escrituras se piden con ese limite al servicio del alumno.
        verify(insights).writings(teacherId, ana.getId(), FROM, TO, 20);
    }

    @Test
    void singleDayPeriodIsLabelledOnceAndDefaultsToToday() {
        when(access.link(teacherId, ana.getId())).thenReturn(link);
        when(access.realName(link)).thenReturn("Ana Pérez");
        when(insights.help(teacherId, ana.getId(), null, null))
                .thenReturn(new StudentHelpResponse(ana.getId(), "2026-09-18", "2026-09-18", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        when(insights.errors(teacherId, ana.getId(), null, null)).thenReturn(
                new StudentErrorsResponse(ana.getId(), "2026-09-18", "2026-09-18", 0, List.of(), List.of()));
        when(insights.writings(teacherId, ana.getId(), null, null, 20)).thenReturn(List.of());
        when(pdfGenerator.generate(any(ReportPdfDocument.class))).thenReturn(new byte[] {1});
        ReportService service = new ReportService(access, insights, pdfGenerator, clock);

        ReportPdfDownload download = service.downloadPdf(teacherId, ana.getId(), null, null);

        assertThat(download.filename()).isEqualTo("reporte-ana01-2026-09-18_2026-09-18.pdf");
        ArgumentCaptor<ReportPdfDocument> captor = ArgumentCaptor.forClass(ReportPdfDocument.class);
        verify(pdfGenerator).generate(captor.capture());
        assertThat(captor.getValue().periodLabel()).isEqualTo("18/09/2026");
        assertThat(captor.getValue().errorTypes()).isEmpty();
        assertThat(captor.getValue().writings()).isEmpty();
    }

    @Test
    void teacherFullNameIsUsedWhenPresent() {
        Teacher named = new Teacher("ana.qa", "ana.qa@colegio.edu.pe", null, "Colegio", "hash", null, false,
                "Ana QA Torres");
        TeacherStudentLink namedLink = new TeacherStudentLink(named, ana, new Classroom(named, "4.º A"), "enc", null);
        when(access.link(teacherId, ana.getId())).thenReturn(namedLink);
        when(access.realName(namedLink)).thenReturn("Ana Pérez");
        when(insights.help(teacherId, ana.getId(), FROM, TO))
                .thenReturn(new StudentHelpResponse(ana.getId(), FROM, TO, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        when(insights.errors(teacherId, ana.getId(), FROM, TO)).thenReturn(
                new StudentErrorsResponse(ana.getId(), FROM, TO, 0, List.of(), List.of()));
        when(insights.writings(teacherId, ana.getId(), FROM, TO, 20)).thenReturn(List.of());
        when(pdfGenerator.generate(any(ReportPdfDocument.class))).thenReturn(new byte[] {1});
        ReportService service = new ReportService(access, insights, pdfGenerator, clock);

        service.downloadPdf(teacherId, ana.getId(), FROM, TO);

        ArgumentCaptor<ReportPdfDocument> captor = ArgumentCaptor.forClass(ReportPdfDocument.class);
        verify(pdfGenerator).generate(captor.capture());
        assertThat(captor.getValue().teacherName()).isEqualTo("Ana QA Torres");
    }

    @Test
    void archivedClassroomIsFlaggedAndFilenameUsesTheSanitizedAlias() {
        Student odd = new Student("Tigre Ñandú/07", "Colegio", "hash");
        Classroom archived = new Classroom(teacher, "5.º A");
        archived.archive();
        TeacherStudentLink oddLink = new TeacherStudentLink(teacher, odd, archived, "enc", null);
        when(access.link(teacherId, odd.getId())).thenReturn(oddLink);
        when(access.realName(oddLink)).thenReturn("Tigre");
        when(insights.help(teacherId, odd.getId(), FROM, TO))
                .thenReturn(new StudentHelpResponse(odd.getId(), FROM, TO, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        when(insights.errors(teacherId, odd.getId(), FROM, TO)).thenReturn(
                new StudentErrorsResponse(odd.getId(), FROM, TO, 0, List.of(), List.of()));
        when(insights.writings(teacherId, odd.getId(), FROM, TO, 20)).thenReturn(List.of());
        when(pdfGenerator.generate(any(ReportPdfDocument.class))).thenReturn(new byte[] {1});
        ReportService service = new ReportService(access, insights, pdfGenerator, clock);

        ReportPdfDownload download = service.downloadPdf(teacherId, odd.getId(), FROM, TO);

        assertThat(download.filename()).isEqualTo("reporte-tigre-nandu-07-2026-09-10_2026-09-17.pdf");
        ArgumentCaptor<ReportPdfDocument> captor = ArgumentCaptor.forClass(ReportPdfDocument.class);
        verify(pdfGenerator).generate(captor.capture());
        assertThat(captor.getValue().classroomName()).isEqualTo("5.º A");
        assertThat(captor.getValue().classroomArchived()).isTrue();
    }

    @Test
    void fileSafeAliasKeepsOnlyLowercaseLettersDigitsUnderscoreAndDash() {
        assertThat(ReportService.fileSafe("alumno_01")).isEqualTo("alumno_01");
        assertThat(ReportService.fileSafe("Tren-Azul")).isEqualTo("tren-azul");
        assertThat(ReportService.fileSafe("..José..")).isEqualTo("jose");
        assertThat(ReportService.fileSafe("???")).isEqualTo("alumno");
    }

    @Test
    void foreignStudentIsForbiddenBeforeAnyPdfWork() {
        when(access.link(teacherId, ana.getId())).thenThrow(new ForbiddenException("Teacher does not have access"));
        ReportService service = new ReportService(access, insights, pdfGenerator, clock);

        assertThatThrownBy(() -> service.downloadPdf(teacherId, ana.getId(), FROM, TO))
                .isInstanceOf(ForbiddenException.class);
        verify(pdfGenerator, never()).generate(any());
    }

    @Test
    void invalidPeriodIsRejected() {
        when(access.link(teacherId, ana.getId())).thenReturn(link);
        ReportService service = new ReportService(access, insights, pdfGenerator, clock);

        assertThatThrownBy(() -> service.downloadPdf(teacherId, ana.getId(), TO, FROM))
                .isInstanceOf(BusinessException.class);
        verify(pdfGenerator, never()).generate(any());
    }

    private void stubInsights() {
        when(access.link(teacherId, ana.getId())).thenReturn(link);
        when(access.realName(link)).thenReturn("Ana Pérez");
        when(insights.help(teacherId, ana.getId(), FROM, TO))
                .thenReturn(new StudentHelpResponse(ana.getId(), FROM, TO, 6, 2, 1, 1, 0, 1, 1, 33.33, 16.67, 16.67, 0, 16.67, 16.67));
        when(insights.errors(teacherId, ana.getId(), FROM, TO)).thenReturn(new StudentErrorsResponse(
                ana.getId(), FROM, TO, 7,
                List.of(
                        new StudentErrorsResponse.ErrorTypeExamples("TILDE", "Tildes y acentuación", 6, List.of(
                                new WordPair("camion", "camión", 3),
                                new WordPair("papa", "papá", 2),
                                new WordPair("arbol", "árbol", 1),
                                new WordPair("avion", "avión", 1),
                                new WordPair("lapiz", "lápiz", 1))),
                        new StudentErrorsResponse.ErrorTypeExamples("H_MUDA", "H muda", 1, List.of(
                                new WordPair("ablar", "hablar", 1)))),
                List.of(new WordPair("camion", "camión", 3), new WordPair("papa", "papá", 2))));
        List<StudentWritingItem> writings = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            writings.add(new StudentWritingItem(UUID.randomUUID(), Instant.parse("2026-09-17T15:00:00Z").minusSeconds(i),
                    "escritura " + i, "final " + i, "ACCEPTED", "Aceptó", false, null, null));
        }
        when(insights.writings(teacherId, ana.getId(), FROM, TO, 20)).thenReturn(writings);
    }
}
