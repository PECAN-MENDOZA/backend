package com.mvp.backend.insights.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mvp.backend.correction.domain.model.CorrectionSession;
import com.mvp.backend.correction.domain.repository.CorrectionSessionRepository;
import com.mvp.backend.correction.domain.repository.WordCorrectionRepository;
import com.mvp.backend.insights.application.dto.ClassroomActivityResponse;
import com.mvp.backend.insights.application.dto.ClassroomActivityResponse.OutcomeCounts;
import com.mvp.backend.insights.application.dto.ClassroomActivityResponse.StudentActivity;
import com.mvp.backend.insights.application.dto.ClassroomErrorsResponse;
import com.mvp.backend.insights.application.dto.ErrorTypeBreakdown;
import com.mvp.backend.insights.application.dto.RecentCorrectionItem;
import com.mvp.backend.insights.domain.Outcome;
import com.mvp.backend.insights.domain.Period;
import com.mvp.backend.teacher.domain.model.Classroom;
import com.mvp.backend.teacher.domain.model.TeacherStudentLink;

/** Consultas descriptivas del salon en un periodo: actividad por alumno, ultimas correcciones y errores por tipo. */
@Service
public class ClassroomInsightsService {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;
    static final int TOP_WORDS = 5;

    private final TeacherAccess access;
    private final CorrectionSessionRepository sessionRepository;
    private final WordCorrectionRepository wordCorrectionRepository;
    private final Clock clock;

    public ClassroomInsightsService(
            TeacherAccess access,
            CorrectionSessionRepository sessionRepository,
            WordCorrectionRepository wordCorrectionRepository,
            Clock clock) {
        this.access = access;
        this.sessionRepository = sessionRepository;
        this.wordCorrectionRepository = wordCorrectionRepository;
        this.clock = clock;
    }

    // Las sesiones del periodo se agregan en memoria (maximo 92 dias por salon, escala de la tesis).
    @Transactional(readOnly = true)
    public ClassroomActivityResponse activity(UUID teacherId, UUID classroomId, String from, String to) {
        Classroom classroom = access.classroom(teacherId, classroomId);
        Period period = Period.parse(from, to, clock);
        List<TeacherStudentLink> links = access.activeLinks(classroomId);
        List<UUID> studentIds = studentIds(links);

        Map<UUID, List<CorrectionSession>> sessionsByStudent = new HashMap<>();
        Map<UUID, Instant> lastActivity = new HashMap<>();
        if (!studentIds.isEmpty()) {
            for (CorrectionSession session : sessionsInPeriod(studentIds, period, Pageable.unpaged())) {
                sessionsByStudent.computeIfAbsent(session.getStudent().getId(), id -> new ArrayList<>()).add(session);
            }
            for (Object[] row : sessionRepository.lastActivityByStudent(studentIds)) {
                lastActivity.put((UUID) row[0], (Instant) row[1]);
            }
        }

        List<StudentActivity> students = links.stream()
                .map(link -> {
                    UUID studentId = link.getStudent().getId();
                    List<CorrectionSession> sessions = sessionsByStudent.getOrDefault(studentId, List.of());
                    return new StudentActivity(
                            studentId,
                            link.getStudent().getUsername(),
                            access.realName(link),
                            lastActivity.get(studentId),
                            sessions.size(),
                            outcomes(sessions));
                })
                // Mas correcciones primero; a igual cantidad, actividad mas reciente primero; sin actividad al final.
                .sorted(Comparator.comparingLong(StudentActivity::correctionsInPeriod).reversed()
                        .thenComparing(StudentActivity::lastActivityAt,
                                Comparator.nullsLast(Comparator.<Instant>reverseOrder())))
                .toList();

        return new ClassroomActivityResponse(
                classroom.getId(), classroom.getName(), period.from().toString(), period.to().toString(), students);
    }

    @Transactional(readOnly = true)
    public List<RecentCorrectionItem> recentCorrections(
            UUID teacherId, UUID classroomId, String from, String to, Integer limit) {
        access.classroom(teacherId, classroomId);
        Period period = Period.parse(from, to, clock);
        List<TeacherStudentLink> links = access.activeLinks(classroomId);
        if (links.isEmpty()) {
            return List.of();
        }
        Map<UUID, TeacherStudentLink> linkByStudent = links.stream()
                .collect(Collectors.toMap(link -> link.getStudent().getId(), Function.identity(),
                        (first, second) -> first, LinkedHashMap::new));

        Pageable page = PageRequest.of(0, CorrectionFacts.clampLimit(limit, DEFAULT_LIMIT, MAX_LIMIT));
        return sessionsInPeriod(List.copyOf(linkByStudent.keySet()), period, page).stream()
                .map(session -> toItem(session, linkByStudent.get(session.getStudent().getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ClassroomErrorsResponse errors(UUID teacherId, UUID classroomId, String from, String to) {
        Classroom classroom = access.classroom(teacherId, classroomId);
        Period period = Period.parse(from, to, clock);
        List<UUID> studentIds = studentIds(access.activeLinks(classroomId));

        WordPairTally tally = WordPairTally.of(studentIds.isEmpty()
                ? List.of()
                : wordCorrectionRepository.wordPairsForStudents(studentIds, period.start(), period.end()));
        List<ErrorTypeBreakdown> types = tally.byType(TOP_WORDS).stream()
                .map(type -> new ErrorTypeBreakdown(
                        type.type().name(), type.type().getLabel(), type.count(), type.examples()))
                .toList();

        return new ClassroomErrorsResponse(
                classroom.getId(), period.from().toString(), period.to().toString(), tally.total(), types);
    }

    private List<CorrectionSession> sessionsInPeriod(List<UUID> studentIds, Period period, Pageable pageable) {
        return sessionRepository.findByStudentIdInAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
                studentIds, period.start(), period.end(), pageable);
    }

    private static List<UUID> studentIds(List<TeacherStudentLink> links) {
        return links.stream().map(link -> link.getStudent().getId()).distinct().toList();
    }

    private static OutcomeCounts outcomes(List<CorrectionSession> sessions) {
        EnumMap<Outcome, Long> counts = CorrectionFacts.countOutcomes(sessions);
        return new OutcomeCounts(
                counts.getOrDefault(Outcome.EDITED, 0L),
                counts.getOrDefault(Outcome.ACCEPTED, 0L),
                counts.getOrDefault(Outcome.REJECTED, 0L),
                counts.getOrDefault(Outcome.UNDONE, 0L),
                counts.getOrDefault(Outcome.UNANSWERED, 0L));
    }

    private RecentCorrectionItem toItem(CorrectionSession session, TeacherStudentLink link) {
        Outcome outcome = CorrectionFacts.outcome(session);
        return new RecentCorrectionItem(
                session.getId(),
                session.getStudent().getId(),
                link.getStudent().getUsername(),
                access.realName(link),
                session.getCreatedAt(),
                session.getOriginalText(),
                session.getCorrectedText(),
                CorrectionFacts.finalText(session),
                outcome.name(),
                outcome.label(),
                session.isInTest(),
                CorrectionFacts.assistance(session));
    }
}
