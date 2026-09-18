package com.mvp.backend.kpi.application.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mvp.backend.correction.domain.model.ErrorType;
import com.mvp.backend.correction.domain.repository.CorrectionSessionRepository;
import com.mvp.backend.correction.domain.repository.WordCorrectionRepository;
import com.mvp.backend.correction.domain.service.WordErrorClassifier;
import com.mvp.backend.insights.domain.Period;
import com.mvp.backend.kpi.application.dto.AcceptanceRateResponse;
import com.mvp.backend.kpi.application.dto.ErrorTypeItem;
import com.mvp.backend.kpi.application.dto.ErrorTypesResponse;
import com.mvp.backend.kpi.application.dto.KpiSummaryResponse;
import com.mvp.backend.kpi.application.dto.TopWordItem;
import com.mvp.backend.kpi.application.dto.TopWordsResponse;
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
    private final Clock clock;

    public KpiService(
            CorrectionSessionRepository sessionRepository,
            WordCorrectionRepository wordCorrectionRepository,
            TeacherStudentLinkRepository linkRepository,
            PersonalDataCipher personalDataCipher,
            Clock clock) {
        this.sessionRepository = sessionRepository;
        this.wordCorrectionRepository = wordCorrectionRepository;
        this.linkRepository = linkRepository;
        this.personalDataCipher = personalDataCipher;
        this.clock = clock;
    }

    @Transactional
    public AcceptanceRateResponse acceptanceRate(UUID teacherId, UUID studentId, String from, String to) {
        requireLink(teacherId, studentId);
        return acceptanceRate(studentId, Period.parse(from, to, clock));
    }

    @Transactional
    public TopWordsResponse topWords(UUID teacherId, UUID studentId, String from, String to) {
        requireLink(teacherId, studentId);
        return topWords(studentId, Period.parse(from, to, clock));
    }

    @Transactional
    public KpiSummaryResponse summary(UUID teacherId, UUID studentId, String from, String to) {
        TeacherStudentLink link = requireLink(teacherId, studentId);
        Period period = Period.parse(from, to, clock);
        var acceptanceRate = acceptanceRate(studentId, period);
        var topWords = topWords(studentId, period);
        return new KpiSummaryResponse(
                studentId,
                personalDataCipher.decrypt(link.getEncryptedStudentRealName()),
                period.from().toString(),
                period.to().toString(),
                acceptanceRate,
                topWords.topWords());
    }

    private AcceptanceRateResponse acceptanceRate(UUID studentId, Period period) {
        var result = sessionRepository.acceptanceSummary(studentId, period.start(), period.end());
        long total = number(result.getTotalSessions());
        long accepted = number(result.getAcceptedSessions());
        long rejected = number(result.getRejectedSessions());
        long unanswered = number(result.getUnansweredSessions());
        long edited = number(result.getEditedSessions());
        return new AcceptanceRateResponse(
                studentId,
                period.from().toString(),
                period.to().toString(),
                total,
                accepted,
                rejected,
                unanswered,
                edited,
                percentage(accepted, total));
    }

    private TopWordsResponse topWords(UUID studentId, Period period) {
        List<TopWordItem> words = wordCorrectionRepository
                .topWords(studentId, period.start(), period.end(), PageRequest.of(0, 10))
                .stream()
                .map(row -> new TopWordItem(
                        (String) row[0],
                        number(row[1]),
                        number(row[2])))
                .toList();
        return new TopWordsResponse(studentId, period.from().toString(), period.to().toString(), words);
    }

    @Transactional
    public ErrorTypesResponse errorTypes(UUID teacherId, UUID studentId, String from, String to) {
        requireLink(teacherId, studentId);
        Period period = Period.parse(from, to, clock);

        EnumMap<ErrorType, Long> counts = new EnumMap<>(ErrorType.class);
        for (Object[] row : wordCorrectionRepository.wordPairsForMonth(studentId, period.start(), period.end())) {
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

        return new ErrorTypesResponse(studentId, period.from().toString(), period.to().toString(), total, items);
    }

    private TeacherStudentLink requireLink(UUID teacherId, UUID studentId) {
        TeacherStudentLink link = linkRepository.findByTeacherIdAndStudentIdAndDeletedAtIsNull(teacherId, studentId)
                .orElseThrow(() -> new ForbiddenException("Teacher does not have access to this student"));
        link.registerAccess();
        return link;
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
}
