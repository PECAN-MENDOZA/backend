package com.mvp.backend.insights.application.service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mvp.backend.insights.application.dto.LiveAttemptItem;
import com.mvp.backend.sentencetest.domain.model.AttemptStatus;
import com.mvp.backend.sentencetest.domain.model.SentenceTest;
import com.mvp.backend.sentencetest.domain.model.TestAttempt;
import com.mvp.backend.sentencetest.domain.repository.TestAttemptRepository;
import com.mvp.backend.sentencetest.domain.repository.TestResponseRepository;
import com.mvp.backend.sentencetest.domain.repository.TestSentenceRepository;
import com.mvp.backend.teacher.domain.model.TeacherStudentLink;

/** Prueba en curso: intentos IN_PROGRESS de los alumnos vinculados al docente y la oracion en la que van. */
@Service
public class LiveTestsService {

    private final TeacherAccess access;
    private final TestAttemptRepository attemptRepository;
    private final TestResponseRepository responseRepository;
    private final TestSentenceRepository sentenceRepository;

    public LiveTestsService(
            TeacherAccess access,
            TestAttemptRepository attemptRepository,
            TestResponseRepository responseRepository,
            TestSentenceRepository sentenceRepository) {
        this.access = access;
        this.attemptRepository = attemptRepository;
        this.responseRepository = responseRepository;
        this.sentenceRepository = sentenceRepository;
    }

    @Transactional(readOnly = true)
    public List<LiveAttemptItem> live(UUID teacherId) {
        List<TeacherStudentLink> links = access.activeLinksOfTeacher(teacherId);
        if (links.isEmpty()) {
            return List.of();
        }
        Map<UUID, TeacherStudentLink> linkByStudent = links.stream()
                .collect(Collectors.toMap(link -> link.getStudent().getId(), Function.identity(),
                        (first, second) -> first, LinkedHashMap::new));

        Map<UUID, Integer> sentenceCountByTest = new HashMap<>();
        return attemptRepository.findByStudentIdInAndStatus(List.copyOf(linkByStudent.keySet()), AttemptStatus.IN_PROGRESS)
                .stream()
                // Quien empezo antes va primero.
                .sorted(Comparator.comparing(TestAttempt::getStartedAt))
                .map(attempt -> toItem(attempt, linkByStudent.get(attempt.getStudent().getId()), sentenceCountByTest))
                .toList();
    }

    private LiveAttemptItem toItem(TestAttempt attempt, TeacherStudentLink link, Map<UUID, Integer> sentenceCountByTest) {
        SentenceTest test = attempt.getTest();
        int finished = (int) responseRepository.countByAttemptIdAndFinishedAtIsNotNull(attempt.getId());
        int sentenceCount = sentenceCountByTest.computeIfAbsent(
                test.getId(), id -> (int) sentenceRepository.countByTestId(id));
        return new LiveAttemptItem(
                attempt.getId(),
                link.getStudent().getId(),
                link.getStudent().getUsername(),
                access.realName(link),
                link.getClassroom().getId(),
                link.getClassroom().getName(),
                test.getCode(),
                test.getTitle(),
                // Oracion en curso: la siguiente a las ya terminadas, acotada a la ultima mientras
                // el intento termina de marcarse COMPLETED.
                Math.min(finished + 1, sentenceCount),
                sentenceCount,
                attempt.getStartedAt());
    }
}
