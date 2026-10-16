package com.mvp.backend.report.application.service;

import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mvp.backend.insights.application.dto.ErrorTypeBreakdown.WordPair;
import com.mvp.backend.insights.application.dto.StudentErrorsResponse;
import com.mvp.backend.insights.application.dto.StudentHelpResponse;
import com.mvp.backend.insights.application.dto.StudentWritingItem;
import com.mvp.backend.insights.application.service.StudentInsightsService;
import com.mvp.backend.insights.application.service.TeacherAccess;
import com.mvp.backend.insights.domain.Period;
import com.mvp.backend.report.application.dto.ReportPdfDocument;
import com.mvp.backend.report.application.dto.ReportPdfDocument.ErrorTypeSection;
import com.mvp.backend.report.application.dto.ReportPdfDocument.HelpSummary;
import com.mvp.backend.report.application.dto.ReportPdfDocument.WordEntry;
import com.mvp.backend.report.application.dto.ReportPdfDocument.WritingEntry;
import com.mvp.backend.report.application.dto.ReportPdfDownload;
import com.mvp.backend.report.infrastructure.pdf.SimplePdfGenerator;
import com.mvp.backend.teacher.domain.model.Classroom;
import com.mvp.backend.teacher.domain.model.Teacher;
import com.mvp.backend.teacher.domain.model.TeacherStudentLink;

/** "Reporte del periodo" en PDF: la misma ficha descriptiva del alumno que ve el docente, para imprimir. */
@Service
public class ReportService {

    static final String TITLE = "Reporte del periodo";
    static final int EXAMPLES_PER_TYPE = 3;
    static final int LAST_WRITINGS = 20;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final TeacherAccess access;
    private final StudentInsightsService insights;
    private final SimplePdfGenerator pdfGenerator;
    private final Clock clock;
    // Con el modulo de pruebas apagado, las escrituras del piloto no llevan la marca "(prueba)".
    private final boolean sentenceTestsEnabled;

    public ReportService(
            TeacherAccess access,
            StudentInsightsService insights,
            SimplePdfGenerator pdfGenerator,
            Clock clock) {
        this(access, insights, pdfGenerator, clock, true);
    }

    @Autowired
    public ReportService(
            TeacherAccess access,
            StudentInsightsService insights,
            SimplePdfGenerator pdfGenerator,
            Clock clock,
            @Value("${app.features.sentence-tests:false}") boolean sentenceTestsEnabled) {
        this.access = access;
        this.insights = insights;
        this.pdfGenerator = pdfGenerator;
        this.clock = clock;
        this.sentenceTestsEnabled = sentenceTestsEnabled;
    }

    @Transactional(readOnly = true)
    public ReportPdfDownload downloadPdf(UUID teacherId, UUID studentId, String from, String to) {
        TeacherStudentLink link = access.link(teacherId, studentId);
        Period period = Period.parse(from, to, clock);

        StudentHelpResponse help = insights.help(teacherId, studentId, from, to);
        StudentErrorsResponse errors = insights.errors(teacherId, studentId, from, to);
        List<StudentWritingItem> writings = insights.writings(teacherId, studentId, from, to, LAST_WRITINGS);

        Classroom classroom = link.getClassroom();
        ReportPdfDocument document = new ReportPdfDocument(
                TITLE,
                access.realName(link),
                link.getStudent().getUsername(),
                classroom.getName(),
                classroom.isArchived(),
                teacherName(link.getTeacher()),
                periodLabel(period.from(), period.to()),
                period.from().toString(),
                period.to().toString(),
                clock.instant(),
                new HelpSummary(help.total(), help.edited(), help.accepted(), help.rejected(), help.undone(),
                        help.unanswered(), help.noChanges()),
                errors.types().stream()
                        .map(type -> new ErrorTypeSection(type.label(), type.count(),
                                words(type.examples().stream().limit(EXAMPLES_PER_TYPE).toList())))
                        .toList(),
                words(errors.practiceWords()),
                writings.stream()
                        .map(item -> new WritingEntry(item.createdAt(), item.originalText(), item.finalText(),
                                item.outcomeLabel(), sentenceTestsEnabled && item.inTest()))
                        .toList(),
                link.getNotes());

        String filename = "reporte-" + fileSafe(link.getStudent().getUsername())
                + "-" + period.from() + "_" + period.to() + ".pdf";
        return new ReportPdfDownload(filename, pdfGenerator.generate(document));
    }

    /** "09/10/2026 – 15/10/2026", o una sola fecha si el periodo es de un dia (fechas de Lima). */
    static String periodLabel(LocalDate from, LocalDate to) {
        return from.equals(to) ? DAY.format(from) : DAY.format(from) + " – " + DAY.format(to);
    }

    /** El alias en el nombre del archivo: minusculas, sin tildes y solo [a-z0-9_-]. */
    static String fileSafe(String alias) {
        String plain = Normalizer.normalize(alias == null ? "" : alias, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("^-+|-+$", "");
        return plain.isEmpty() ? "alumno" : plain;
    }

    /** Nombre completo del docente; si no lo tiene, su usuario como antes. */
    private static String teacherName(Teacher teacher) {
        String fullName = teacher.getFullName();
        return fullName == null || fullName.isBlank() ? teacher.getUsername() : fullName;
    }

    private static List<WordEntry> words(List<WordPair> pairs) {
        return pairs.stream().map(pair -> new WordEntry(pair.original(), pair.corrected(), pair.count())).toList();
    }
}
