package com.mvp.backend.report.application.service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

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
import com.mvp.backend.teacher.domain.model.TeacherStudentLink;

/** "Reporte del periodo" en PDF: la misma ficha descriptiva del alumno que ve el docente, para imprimir. */
@Service
public class ReportService {

    static final String TITLE = "Reporte del periodo";
    static final int EXAMPLES_PER_TYPE = 3;
    static final int LAST_WRITINGS = 20;

    private final TeacherAccess access;
    private final StudentInsightsService insights;
    private final SimplePdfGenerator pdfGenerator;
    private final Clock clock;

    public ReportService(
            TeacherAccess access,
            StudentInsightsService insights,
            SimplePdfGenerator pdfGenerator,
            Clock clock) {
        this.access = access;
        this.insights = insights;
        this.pdfGenerator = pdfGenerator;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ReportPdfDownload downloadPdf(UUID teacherId, UUID studentId, String from, String to) {
        TeacherStudentLink link = access.link(teacherId, studentId);
        Period period = Period.parse(from, to, clock);

        StudentHelpResponse help = insights.help(teacherId, studentId, from, to);
        StudentErrorsResponse errors = insights.errors(teacherId, studentId, from, to);
        List<StudentWritingItem> writings = insights.writings(teacherId, studentId, from, to, LAST_WRITINGS);

        ReportPdfDocument document = new ReportPdfDocument(
                TITLE,
                access.realName(link),
                link.getStudent().getUsername(),
                link.getTeacher().getUsername(),
                period.label(),
                period.from().toString(),
                period.to().toString(),
                clock.instant(),
                new HelpSummary(help.total(), help.edited(), help.accepted(), help.rejected(), help.undone(),
                        help.unanswered()),
                errors.types().stream()
                        .map(type -> new ErrorTypeSection(type.label(), type.count(),
                                words(type.examples().stream().limit(EXAMPLES_PER_TYPE).toList())))
                        .toList(),
                words(errors.practiceWords()),
                writings.stream()
                        .map(item -> new WritingEntry(item.createdAt(), item.originalText(), item.finalText(),
                                item.outcomeLabel(), item.inTest()))
                        .toList(),
                link.getNotes());

        String filename = "reporte-" + link.getStudent().getUsername() + "-" + period.from() + "_" + period.to() + ".pdf";
        return new ReportPdfDownload(filename, pdfGenerator.generate(document));
    }

    private static List<WordEntry> words(List<WordPair> pairs) {
        return pairs.stream().map(pair -> new WordEntry(pair.original(), pair.corrected(), pair.count())).toList();
    }
}
