package com.mvp.backend.kpi.application.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mvp.backend.correction.domain.model.ErrorType;
import com.mvp.backend.correction.domain.repository.CorrectionSessionRepository;
import com.mvp.backend.correction.domain.repository.WordCorrectionRepository;
import com.mvp.backend.correction.domain.service.WordErrorClassifier;
import com.mvp.backend.kpi.application.dto.AcceptanceRateResponse;
import com.mvp.backend.kpi.application.dto.AcceptanceTrendResponse;
import com.mvp.backend.kpi.application.dto.ErrorTypeItem;
import com.mvp.backend.kpi.application.dto.ErrorTypesResponse;
import com.mvp.backend.kpi.application.dto.KpiSummaryResponse;
import com.mvp.backend.kpi.application.dto.MonthlyAcceptancePoint;
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
    public TopWordsResponse topWords(UUID teacherId, UUID studentId, String month) {
        requireLink(teacherId, studentId);
        return topWords(studentId, parseMonth(month));
    }

    @Transactional
    public KpiSummaryResponse summary(UUID teacherId, UUID studentId, String month) {
        TeacherStudentLink link = requireLink(teacherId, studentId);
        YearMonth parsedMonth = parseMonth(month);
        var acceptanceRate = acceptanceRate(studentId, parsedMonth);
        var topWords = topWords(studentId, parsedMonth);
        return new KpiSummaryResponse(
                studentId,
                personalDataCipher.decrypt(link.getEncryptedStudentRealName()),
                parsedMonth.toString(),
                acceptanceRate,
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

    private TopWordsResponse topWords(UUID studentId, YearMonth month) {
        MonthRange range = range(month);
        List<TopWordItem> words = wordCorrectionRepository
                .topWords(studentId, range.start(), range.end(), PageRequest.of(0, 10))
                .stream()
                .map(row -> new TopWordItem(
                        (String) row[0],
                        number(row[1]),
                        number(row[2])))
                .toList();
        return new TopWordsResponse(studentId, month.toString(), words);
    }

    @Transactional
    public ErrorTypesResponse errorTypes(UUID teacherId, UUID studentId, String month) {
        requireLink(teacherId, studentId);
        YearMonth parsedMonth = parseMonth(month);
        MonthRange range = range(parsedMonth);

        EnumMap<ErrorType, Long> counts = new EnumMap<>(ErrorType.class);
        for (Object[] row : wordCorrectionRepository.wordPairsForMonth(studentId, range.start(), range.end())) {
            ErrorType type = WordErrorClassifier.classify((String) row[0], (String) row[1]);
            counts.merge(type, 1L, Long::sum);
        }

        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        List<ErrorTypeItem> items = counts.entrySet().stream()
                .map(entry -> new ErrorTypeItem(
                        entry.getKey().name(),
                        entry.getKey().getLabel(),
                        entry.getValue(),
                        percentage(entry.getValue(), total)))
                .sorted(Comparator.comparingLong(ErrorTypeItem::count).reversed())
                .toList();

        return new ErrorTypesResponse(studentId, parsedMonth.toString(), total, items);
    }

    private static final int MAX_TREND_MONTHS = 24;

    @Transactional
    public AcceptanceTrendResponse acceptanceTrend(UUID teacherId, UUID studentId, String from, String to) {
        requireLink(teacherId, studentId);
        YearMonth start = parseMonth(from);
        YearMonth end = parseMonth(to);
        if (start.isAfter(end)) {
            throw new BusinessException("'from' must not be after 'to'");
        }
        long span = start.until(end, java.time.temporal.ChronoUnit.MONTHS) + 1;
        if (span > MAX_TREND_MONTHS) {
            throw new BusinessException("Trend range must not exceed " + MAX_TREND_MONTHS + " months");
        }

        Instant rangeStart = start.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant rangeEnd = end.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        Map<String, long[]> byMonth = new HashMap<>();
        for (Object[] row : sessionRepository.monthlyAcceptance(studentId, rangeStart, rangeEnd)) {
            byMonth.put((String) row[0], new long[] {number(row[1]), number(row[2])});
        }

        List<MonthlyAcceptancePoint> series = new ArrayList<>();
        for (YearMonth month = start; !month.isAfter(end); month = month.plusMonths(1)) {
            long[] totals = byMonth.getOrDefault(month.toString(), new long[] {0L, 0L});
            long total = totals[0];
            long accepted = totals[1];
            series.add(new MonthlyAcceptancePoint(month.toString(), total, accepted, percentage(accepted, total)));
        }

        return new AcceptanceTrendResponse(studentId, start.toString(), end.toString(), series);
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
