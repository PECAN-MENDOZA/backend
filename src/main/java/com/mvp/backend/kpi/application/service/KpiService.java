package com.mvp.backend.kpi.application.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mvp.backend.correction.domain.model.ErrorType;
import com.mvp.backend.correction.domain.repository.CorrectionSessionRepository;
import com.mvp.backend.correction.domain.repository.WordCorrectionRepository;
import com.mvp.backend.kpi.application.dto.AcceptanceRateResponse;
import com.mvp.backend.kpi.application.dto.ErrorDistributionItem;
import com.mvp.backend.kpi.application.dto.ErrorDistributionResponse;
import com.mvp.backend.kpi.application.dto.KpiSummaryResponse;
import com.mvp.backend.kpi.application.dto.TopWordItem;
import com.mvp.backend.kpi.application.dto.TopWordsResponse;
import com.mvp.backend.shared.exception.BusinessException;
import com.mvp.backend.shared.exception.ForbiddenException;
import com.mvp.backend.shared.security.PersonalDataCipher;
import com.mvp.backend.teacher.domain.model.TeacherStudentLink;
import com.mvp.backend.teacher.domain.repository.TeacherStudentLinkRepository;

@Service
public class KpiService {

    private final CorrectionSessionRepository sessionRepository;
    private final WordCorrectionRepository wordCorrectionRepository;
    private final TeacherStudentLinkRepository linkRepository;
    private final PersonalDataCipher personalDataCipher;

    public KpiService(
            CorrectionSessionRepository sessionRepository,
            WordCorrectionRepository wordCorrectionRepository,
            TeacherStudentLinkRepository linkRepository,
            PersonalDataCipher personalDataCipher) {
        this.sessionRepository = sessionRepository;
        this.wordCorrectionRepository = wordCorrectionRepository;
        this.linkRepository = linkRepository;
        this.personalDataCipher = personalDataCipher;
    }

    @Transactional
    public AcceptanceRateResponse acceptanceRate(UUID teacherId, UUID studentId, String month) {
        requireLink(teacherId, studentId);
        return acceptanceRate(studentId, parseMonth(month));
    }

    @Transactional
    public ErrorDistributionResponse errorDistribution(UUID teacherId, UUID studentId, String month) {
        requireLink(teacherId, studentId);
        return errorDistribution(studentId, parseMonth(month));
    }

    @Transactional
    public TopWordsResponse topWords(UUID teacherId, UUID studentId, String month) {
        requireLink(teacherId, studentId);
        return topWords(studentId, parseMonth(month));
    }

    @Transactional
    public KpiSummaryResponse summary(UUID teacherId, UUID studentId, String month) {
        TeacherStudentLink link = requireLink(teacherId, studentId);
        YearMonth parsedMonth = parseMonth(month);
        var acceptanceRate = acceptanceRate(studentId, parsedMonth);
        var distribution = errorDistribution(studentId, parsedMonth);
        var topWords = topWords(studentId, parsedMonth);
        return new KpiSummaryResponse(
                studentId,
                personalDataCipher.decrypt(link.getEncryptedStudentRealName()),
                parsedMonth.toString(),
                acceptanceRate,
                distribution.distribution(),
                topWords.topWords());
    }

    private AcceptanceRateResponse acceptanceRate(UUID studentId, YearMonth month) {
        MonthRange range = range(month);
        var result = sessionRepository.acceptanceSummary(studentId, range.start(), range.end());
        long total = number(result.getTotalSessions());
        long accepted = number(result.getAcceptedSessions());
        long rejected = number(result.getRejectedSessions());
        long unanswered = number(result.getUnansweredSessions());
        return new AcceptanceRateResponse(
                studentId,
                month.toString(),
                total,
                accepted,
                rejected,
                unanswered,
                percentage(accepted, total));
    }

    private ErrorDistributionResponse errorDistribution(UUID studentId, YearMonth month) {
        MonthRange range = range(month);
        List<Object[]> rows = wordCorrectionRepository.errorDistribution(studentId, range.start(), range.end());
        long total = rows.stream().mapToLong(row -> number(row[1])).sum();
        List<ErrorDistributionItem> distribution = rows.stream()
                .map(row -> new ErrorDistributionItem(
                        (ErrorType) row[0],
                        number(row[1]),
                        percentage(number(row[1]), total)))
                .toList();
        return new ErrorDistributionResponse(studentId, month.toString(), total, distribution);
    }

    private TopWordsResponse topWords(UUID studentId, YearMonth month) {
        MonthRange range = range(month);
        List<TopWordItem> words = wordCorrectionRepository
                .topWords(studentId, range.start(), range.end(), PageRequest.of(0, 10))
                .stream()
                .map(row -> new TopWordItem(
                        (String) row[0],
                        (ErrorType) row[1],
                        number(row[2]),
                        round(((Number) row[3]).doubleValue()),
                        number(row[4])))
                .toList();
        return new TopWordsResponse(studentId, month.toString(), words);
    }

    private TeacherStudentLink requireLink(UUID teacherId, UUID studentId) {
        TeacherStudentLink link = linkRepository.findByTeacherIdAndStudentIdAndDeletedAtIsNull(teacherId, studentId)
                .orElseThrow(() -> new ForbiddenException("Teacher does not have access to this student"));
        link.registerAccess();
        return link;
    }

    private YearMonth parseMonth(String month) {
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException exception) {
            throw new BusinessException("Month must use YYYY-MM format");
        }
    }

    private MonthRange range(YearMonth month) {
        Instant start = month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant end = month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return new MonthRange(start, end);
    }

    private long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private double percentage(long amount, long total) {
        return total == 0 ? 0 : round(amount * 100.0 / total);
    }

    private double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private record MonthRange(Instant start, Instant end) {
    }
}
