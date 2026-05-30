package com.mvp.backend.report.application.service;

import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mvp.backend.kpi.application.dto.AcceptanceRateResponse;
import com.mvp.backend.kpi.application.dto.ErrorDistributionItem;
import com.mvp.backend.kpi.application.dto.KpiSummaryResponse;
import com.mvp.backend.kpi.application.dto.TopWordItem;
import com.mvp.backend.kpi.application.service.KpiService;
import com.mvp.backend.report.application.dto.ReportAvailabilityResponse;
import com.mvp.backend.report.application.dto.ReportPdfDownload;
import com.mvp.backend.report.domain.model.MonthlyReport;
import com.mvp.backend.report.domain.repository.MonthlyReportRepository;
import com.mvp.backend.report.infrastructure.pdf.SimplePdfGenerator;
import com.mvp.backend.shared.exception.BusinessException;
import com.mvp.backend.shared.exception.NotFoundException;
import com.mvp.backend.teacher.domain.model.Teacher;
import com.mvp.backend.teacher.domain.model.TeacherStudentLink;
import com.mvp.backend.teacher.domain.repository.TeacherRepository;
import com.mvp.backend.teacher.domain.repository.TeacherStudentLinkRepository;

@Service
public class ReportService {

    private final KpiService kpiService;
    private final MonthlyReportRepository monthlyReportRepository;
    private final TeacherStudentLinkRepository linkRepository;
    private final TeacherRepository teacherRepository;
    private final SimplePdfGenerator pdfGenerator;

    public ReportService(
            KpiService kpiService,
            MonthlyReportRepository monthlyReportRepository,
            TeacherStudentLinkRepository linkRepository,
            TeacherRepository teacherRepository,
            SimplePdfGenerator pdfGenerator) {
        this.kpiService = kpiService;
        this.monthlyReportRepository = monthlyReportRepository;
        this.linkRepository = linkRepository;
        this.teacherRepository = teacherRepository;
        this.pdfGenerator = pdfGenerator;
    }

    @Transactional(readOnly = true)
    public ReportAvailabilityResponse availability(UUID teacherId, UUID studentId, String month) {
        YearMonth parsedMonth = parseMonth(month);
        KpiSummaryResponse summary = kpiService.summary(teacherId, studentId, parsedMonth.toString());
        TeacherStudentLink link = requireLink(teacherId, studentId);
        Optional<MonthlyReport> snapshot = monthlyReportRepository.findByStudentIdAndMonth(studentId, parsedMonth.atDay(1));
        boolean available = snapshot.isPresent() || summary.acceptanceRate().totalSubmissions() > 0;
        String filename = available ? filename(summary.name(), parsedMonth) : null;
        return new ReportAvailabilityResponse(
                studentId,
                link.getStudent().getUsername(),
                summary.name(),
                parsedMonth.toString(),
                available,
                snapshot.isPresent() ? "HISTORICAL_SNAPSHOT" : "LIVE_KPI",
                snapshot.map(MonthlyReport::getGeneratedAt).orElse(null),
                filename);
    }

    @Transactional(readOnly = true)
    public ReportPdfDownload downloadPdf(UUID teacherId, UUID studentId, String month) {
        YearMonth parsedMonth = parseMonth(month);
        KpiSummaryResponse summary = kpiService.summary(teacherId, studentId, parsedMonth.toString());
        TeacherStudentLink link = requireLink(teacherId, studentId);
        Optional<MonthlyReport> snapshot = monthlyReportRepository.findByStudentIdAndMonth(studentId, parsedMonth.atDay(1));

        if (summary.acceptanceRate().totalSubmissions() == 0 && snapshot.isEmpty()) {
            throw new NotFoundException("Report not found for requested month");
        }

        Teacher teacher = teacherRepository.findById(teacherId)
                .orElseThrow(() -> new NotFoundException("Teacher not found"));

        Instant generatedAt = snapshot.map(MonthlyReport::getGeneratedAt).orElseGet(Instant::now);
        String filename = filename(summary.name(), parsedMonth);
        byte[] content = pdfGenerator.generate(linesForPdf(link, teacher, summary, generatedAt));
        return new ReportPdfDownload(filename, content);
    }

    private List<String> linesForPdf(
            TeacherStudentLink link,
            Teacher teacher,
            KpiSummaryResponse summary,
            Instant generatedAt) {
        List<String> lines = new ArrayList<>();
        AcceptanceRateResponse acceptance = summary.acceptanceRate();

        lines.add("Reporte mensual consolidado");
        lines.add("");
        lines.add("Estudiante: " + summary.name());
        lines.add("Alias: " + link.getStudent().getUsername());
        lines.add("Docente: " + teacher.getUsername());
        lines.add("Mes del reporte: " + monthLabel(summary.month()));
        lines.add("Fuente de datos: " + (acceptance.totalSubmissions() > 0 ? "KPI consolidado" : "Sin envios en el mes"));
        lines.add("Fecha de generacion: " + generatedAt);
        lines.add("");
        lines.add("Tasa de aceptacion: " + acceptance.acceptanceRatePercentage() + "%");
        lines.add("Sugerencias aceptadas: " + acceptance.totalAccepted());
        lines.add("Sugerencias rechazadas: " + acceptance.totalRejected());
        lines.add("Sin respuesta: " + acceptance.unanswered());
        lines.add("Total de envios: " + acceptance.totalSubmissions());
        lines.add("");
        lines.add("Distribucion de errores por tipo");
        appendDistribution(lines, summary.errorsByType());
        lines.add("");
        lines.add("Palabras recurrentes");
        appendTopWords(lines, summary.topWords());
        lines.add("");
        lines.add("Notas docentes");
        lines.add(link.getNotes() == null || link.getNotes().isBlank() ? "Sin notas disponibles." : link.getNotes());
        return lines;
    }

    private void appendDistribution(List<String> lines, List<ErrorDistributionItem> items) {
        if (items.isEmpty()) {
            lines.add("No hay errores registrados en el periodo.");
            return;
        }
        for (ErrorDistributionItem item : items) {
            lines.add("- " + item.type() + ": " + item.count() + " (" + item.percentage() + "%)");
        }
    }

    private void appendTopWords(List<String> lines, List<TopWordItem> items) {
        if (items.isEmpty()) {
            lines.add("No hay palabras recurrentes para este mes.");
            return;
        }
        for (TopWordItem item : items) {
            lines.add("- " + item.originalWord()
                    + " | tipo: " + item.mostCommonType()
                    + " | frecuencia: " + item.frequency()
                    + " | confianza: " + item.averageConfidence()
                    + " | aceptadas: " + item.acceptedCorrectionCount());
        }
    }

    private TeacherStudentLink requireLink(UUID teacherId, UUID studentId) {
        return linkRepository.findByTeacherIdAndStudentIdAndDeletedAtIsNull(teacherId, studentId)
                .orElseThrow(() -> new com.mvp.backend.shared.exception.ForbiddenException(
                        "Teacher does not have access to this student"));
    }

    private YearMonth parseMonth(String month) {
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException exception) {
            throw new BusinessException("Month must use YYYY-MM format");
        }
    }

    private String filename(String studentName, YearMonth month) {
        return "reporte-" + slugify(studentName) + "-" + month + ".pdf";
    }

    private String slugify(String value) {
        String normalized = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return normalized.isBlank() ? "estudiante" : normalized;
    }

    private String monthLabel(String month) {
        YearMonth parsedMonth = YearMonth.parse(month);
        return parsedMonth.getMonth().getDisplayName(TextStyle.FULL, new Locale("es", "PE"))
                + " " + parsedMonth.getYear();
    }
}
