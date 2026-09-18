package com.mvp.backend.insights.application.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mvp.backend.correction.domain.model.CorrectionSession;
import com.mvp.backend.correction.domain.repository.CorrectionSessionRepository;
import com.mvp.backend.correction.domain.repository.WordCorrectionRepository;
import com.mvp.backend.insights.application.dto.StudentErrorsResponse;
import com.mvp.backend.insights.application.dto.StudentErrorsResponse.ErrorTypeExamples;
import com.mvp.backend.insights.application.dto.StudentHelpResponse;
import com.mvp.backend.insights.application.dto.StudentTestSummary;
import com.mvp.backend.insights.application.dto.StudentTestSummary.SentenceSummary;
import com.mvp.backend.insights.application.dto.StudentWritingItem;
import com.mvp.backend.insights.domain.Outcome;
import com.mvp.backend.insights.domain.Period;
import com.mvp.backend.sentencetest.application.service.TestExportCsv;
import com.mvp.backend.sentencetest.domain.model.AttemptStatus;
import com.mvp.backend.sentencetest.domain.model.AutoErrorDetail;
import com.mvp.backend.sentencetest.domain.model.TestAttempt;
import com.mvp.backend.sentencetest.domain.model.TestResponse;
import com.mvp.backend.sentencetest.domain.model.TestSentence;
import com.mvp.backend.sentencetest.domain.repository.TestAttemptRepository;
import com.mvp.backend.sentencetest.domain.repository.TestResponseRepository;

/** Ficha descriptiva de un alumno vinculado: errores, respuesta a la ayuda y escrituras en un periodo; pruebas terminadas. */
@Service
public class StudentInsightsService {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 200;
    static final int EXAMPLES_PER_TYPE = 5;
    static final int PRACTICE_MIN_COUNT = 2;
    static final int PRACTICE_MAX_WORDS = 20;

    private final TeacherAccess access;
    private final CorrectionSessionRepository sessionRepository;
    private final WordCorrectionRepository wordCorrectionRepository;
    private final TestAttemptRepository attemptRepository;
    private final TestResponseRepository responseRepository;
    private final Clock clock;

    public StudentInsightsService(
            TeacherAccess access,
            CorrectionSessionRepository sessionRepository,
            WordCorrectionRepository wordCorrectionRepository,
            TestAttemptRepository attemptRepository,
            TestResponseRepository responseRepository,
            Clock clock) {
        this.access = access;
        this.sessionRepository = sessionRepository;
        this.wordCorrectionRepository = wordCorrectionRepository;
        this.attemptRepository = attemptRepository;
        this.responseRepository = responseRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public StudentErrorsResponse errors(UUID teacherId, UUID studentId, String from, String to) {
        access.link(teacherId, studentId);
        Period period = Period.parse(from, to, clock);

        WordPairTally tally = WordPairTally.of(
                wordCorrectionRepository.wordPairsForStudents(List.of(studentId), period.start(), period.end()));
        List<ErrorTypeExamples> types = tally.byType(EXAMPLES_PER_TYPE).stream()
                .map(type -> new ErrorTypeExamples(
                        type.type().name(), type.type().getLabel(), type.count(), type.examples()))
                .toList();

        return new StudentErrorsResponse(
                studentId,
                period.from().toString(),
                period.to().toString(),
                tally.total(),
                types,
                tally.repeated(PRACTICE_MIN_COUNT, PRACTICE_MAX_WORDS));
    }

    @Transactional(readOnly = true)
    public StudentHelpResponse help(UUID teacherId, UUID studentId, String from, String to) {
        access.link(teacherId, studentId);
        Period period = Period.parse(from, to, clock);

        List<CorrectionSession> sessions = sessionsInPeriod(studentId, period, Pageable.unpaged());
        EnumMap<Outcome, Long> counts = CorrectionFacts.countOutcomes(sessions);
        long total = sessions.size();
        long edited = counts.getOrDefault(Outcome.EDITED, 0L);
        long accepted = counts.getOrDefault(Outcome.ACCEPTED, 0L);
        long rejected = counts.getOrDefault(Outcome.REJECTED, 0L);
        long undone = counts.getOrDefault(Outcome.UNDONE, 0L);
        long unanswered = counts.getOrDefault(Outcome.UNANSWERED, 0L);
        return new StudentHelpResponse(
                studentId,
                period.from().toString(),
                period.to().toString(),
                total, edited, accepted, rejected, undone, unanswered,
                percentage(edited, total),
                percentage(accepted, total),
                percentage(rejected, total),
                percentage(undone, total),
                percentage(unanswered, total));
    }

    @Transactional(readOnly = true)
    public List<StudentWritingItem> writings(UUID teacherId, UUID studentId, String from, String to, Integer limit) {
        access.link(teacherId, studentId);
        Period period = Period.parse(from, to, clock);

        Pageable page = PageRequest.of(0, CorrectionFacts.clampLimit(limit, DEFAULT_LIMIT, MAX_LIMIT));
        return sessionsInPeriod(studentId, period, page).stream()
                .map(StudentInsightsService::toWriting)
                .toList();
    }

    /** Solo intentos COMPLETED (los excluidos por el investigador aparecen con excluded = true; los cancelados no). */
    @Transactional(readOnly = true)
    public List<StudentTestSummary> tests(UUID teacherId, UUID studentId) {
        access.link(teacherId, studentId);

        List<TestAttempt> attempts =
                attemptRepository.findByStudentIdAndStatusOrderByCompletedAtDesc(studentId, AttemptStatus.COMPLETED);
        if (attempts.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<TestResponse>> responsesByAttempt = new HashMap<>();
        List<UUID> attemptIds = attempts.stream().map(TestAttempt::getId).toList();
        for (TestResponse response : responseRepository.findWithSentenceByAttemptIdIn(attemptIds)) {
            responsesByAttempt.computeIfAbsent(response.getAttempt().getId(), id -> new ArrayList<>()).add(response);
        }
        return attempts.stream()
                .map(attempt -> new StudentTestSummary(
                        attempt.getId(),
                        attempt.getTest().getCode(),
                        attempt.getTest().getTitle(),
                        attempt.getCompletedAt(),
                        attempt.isExcluded(),
                        responsesByAttempt.getOrDefault(attempt.getId(), List.of()).stream()
                                .map(StudentInsightsService::toSentence)
                                .toList()))
                .toList();
    }

    private List<CorrectionSession> sessionsInPeriod(UUID studentId, Period period, Pageable pageable) {
        return sessionRepository.findByStudentIdInAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
                List.of(studentId), period.start(), period.end(), pageable);
    }

    private static StudentWritingItem toWriting(CorrectionSession session) {
        Outcome outcome = CorrectionFacts.outcome(session);
        return new StudentWritingItem(
                session.getId(),
                session.getCreatedAt(),
                session.getOriginalText(),
                CorrectionFacts.finalText(session),
                outcome.name(),
                outcome.label(),
                session.isInTest(),
                CorrectionFacts.assistance(session),
                CorrectionFacts.testCode(session));
    }

    private static SentenceSummary toSentence(TestResponse response) {
        TestSentence sentence = response.getSentence();
        return new SentenceSummary(
                response.getPosition(),
                sentence.getKind().name(),
                sentence.getAssistance().name(),
                sentence.getReferenceText(),
                response.getFinalText(),
                response.isSkipped(),
                response.effectiveErrorCount(),
                TestExportCsv.errorSource(response),
                AutoErrorDetail.edits(response.getAutoErrorDetail()).stream()
                        .map(edit -> new StudentTestSummary.Edit(edit.type(), edit.expected(), edit.written()))
                        .toList(),
                response.getDurationFromFirstKeyMs());
    }

    /** Porcentaje sobre el total con dos decimales; 0 sin total. */
    private static double percentage(long amount, long total) {
        if (total == 0) {
            return 0;
        }
        return BigDecimal.valueOf(amount * 100.0 / total).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
